/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.athenz.auth.token;

import com.nimbusds.jose.*;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.*;
import com.yahoo.athenz.auth.KeyStore;
import com.yahoo.athenz.auth.token.jwts.JwtsHelper;
import com.yahoo.athenz.auth.token.jwts.JwtsSigningKeyResolver;
import com.yahoo.athenz.auth.util.CryptoException;
import com.yahoo.athenz.auth.util.StringUtils;

import java.security.PublicKey;
import java.util.Date;
import java.util.List;

public class OAuth2Token {

    public final static String SYS_AUTH_DOMAIN = "sys.auth";
    public final static String ZTS_SERVICE_NAME = "zts";

    public static final String CLAIM_VERSION = "ver";
    public static final String CLAIM_AUTH_TIME = "auth_time";

    protected int version;
    protected long expiryTime;
    protected long issueTime;
    protected long authTime;
    protected long notBeforeTime;
    protected String audience;
    protected String issuer;
    protected String subject;
    protected String jwtId;
    protected String clientIdDomainName;
    protected String clientIdServiceName;
    protected JWTClaimsSet claimsSet = null;
    protected static DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(null, null);

    public OAuth2Token() {
    }

    public OAuth2Token(final String token, JwtsSigningKeyResolver keyResolver) {

        try {

            // if the keyResolver is null and the token does not have
            // a signature we're going to treat and parse it as a jwt
            // without any claim validation

            if (keyResolver == null) {
                claimsSet = JwtsHelper.parseJWTWithoutSignature(token);
            } else {

                // create a processor and process the token which does signature verification
                // along with standard claims validation (expiry, not before, etc.)

                ConfigurableJWTProcessor<SecurityContext> jwtProcessor = JwtsHelper.getJWTProcessor(keyResolver);
                claimsSet = jwtProcessor.process(token, null);
            }

            setTokenFields();

        } catch (Exception ex) {
            throw new CryptoException("Unable to parse token: " + ex.getMessage());
        }
    }

    public OAuth2Token(final String token, PublicKey publicKey) {

        try {

            // if the public key is null and the token does not have
            // a signature we're going to treat and parse it as a jwt
            // without any claim validation

            if (publicKey == null) {
                claimsSet = JwtsHelper.parseJWTWithoutSignature(token);
            } else {

                // Create a verifier and parse the token and verify the signature

                JWSVerifier verifier = JwtsHelper.getJWSVerifier(publicKey);
                SignedJWT signedJWT = SignedJWT.parse(token);
                if (!signedJWT.verify(verifier)) {
                    throw new CryptoException("Unable to verify token signature");
                }

                // Extract and verify the claims (expiry, not before, etc.)

                claimsSet = signedJWT.getJWTClaimsSet();
                claimsVerifier.verify(claimsSet, null);
            }

            setTokenFields();

        } catch (Exception ex) {
            throw new CryptoException("Unable to parse token: " + ex.getMessage());
        }
    }

    public OAuth2Token(final String token, KeyStore publicKeyProvider, final String oauth2Issuer) {

        try {
            // first parse the token to extract the fields from the body and header

            SignedJWT signedJWT = SignedJWT.parse(token);

            // Extract and verify the claims (expiry, not before, etc.)

            claimsSet = signedJWT.getJWTClaimsSet();
            claimsVerifier.verify(claimsSet, null);

            // extract the issuer and subject of the token. there are two possible
            // supported cases:
            //  1. issuer matches our oauth2 issuer so we're dealing with a token signed by ZTS
            //  2. otherwise, the token is signed by service itself so issuer and subject
            //     must be present and equal

            final String issuer = claimsSet.getIssuer();
            final String subject = claimsSet.getSubject();
            final String audience = getClaimAudience();

            if (StringUtils.isEmpty(issuer) || StringUtils.isEmpty(subject) || StringUtils.isEmpty(audience)) {
                throw new CryptoException("Invalid token: missing issuer, subject or audience");
            }

            boolean athenzIssuer = oauth2Issuer.equals(issuer);
            if (!athenzIssuer) {
                if (!issuer.equals(subject)) {
                    throw new CryptoException("Invalid token: mismatched issuer (" + issuer
                            + ") and subject (" + subject + ")");
                }

                // extract the audience of the token. for athenz support case this
                // value must be present and match the oidc issuer for the server

                if (!audience.equals(oauth2Issuer)) {
                    throw new CryptoException("Invalid token: mismatched audience (" + audience
                            + ") and issuer (" + oauth2Issuer + ")");
                }
            }

            // our subject is the service identifier so we'll extract
            // the domain and service values

            int idx = subject.lastIndexOf('.');
            if (idx < 0) {
                throw new CryptoException("Invalid token: missing domain and service: " + subject);
            }

            clientIdDomainName = subject.substring(0, idx);
            clientIdServiceName = subject.substring(idx + 1);

            // if the token is an athenz token then we only support validation
            // based on public key

            JWSHeader header = signedJWT.getHeader();
            if (athenzIssuer) {
                validateWithPublicKey(signedJWT, header, publicKeyProvider, SYS_AUTH_DOMAIN, ZTS_SERVICE_NAME);
            } else {
                // if the header is signed with HMAC then we're going to get the
                // secret from the keystore otherwise we're going to get the public key
                // from the keystore for the domain/service/key id. The validate
                // methods will throw crypto exceptions if they're not able to
                // validate the signature

                if (isHMACAlgorithm(header.getAlgorithm())) {
                    validateWithSecret(signedJWT, publicKeyProvider);
                } else {
                    validateWithPublicKey(signedJWT, header, publicKeyProvider, clientIdDomainName, clientIdServiceName);
                }
            }

            // if we got this far then we have a valid token so
            // we'll set our claims set and extract the fields

            setTokenFields();

        } catch (Exception ex) {
            throw new CryptoException("Unable to parse token: " + ex.getMessage());
        }
    }

    boolean isHMACAlgorithm(JWSAlgorithm algorithm) {
        return algorithm.equals(JWSAlgorithm.HS256) || algorithm.equals(JWSAlgorithm.HS384)
                || algorithm.equals(JWSAlgorithm.HS512);
    }

    void validateWithSecret(SignedJWT signedJWT, KeyStore publicKeyProvider) throws JOSEException {

        // get the secret for the given domain/service
        // and create a verifier

        final byte[] secret = publicKeyProvider.getServiceSecret(clientIdDomainName, clientIdServiceName);
        if (secret == null) {
            throw new CryptoException("Invalid token: unable to get secret for " + clientIdDomainName
                    + "." + clientIdServiceName + " service");
        }

        JWSVerifier verifier = JwtsHelper.getJWSVerifier(secret);
        if (!signedJWT.verify(verifier)) {
            throw new CryptoException("Unable to verify token signature");
        }
    }

    void validateWithPublicKey(SignedJWT signedJWT, JWSHeader header, KeyStore publicKeyProvider,
            final String domainName, final String serviceName) throws JOSEException {

        final String keyId = header.getKeyID();
        if (StringUtils.isEmpty(keyId)) {
            throw new CryptoException("Invalid token: missing key id");
        }

        // get the public key for the domain/service/key id
        // and create a verifier

        final PublicKey publicKey = publicKeyProvider.getServicePublicKey(domainName,
                serviceName, keyId);
        if (publicKey == null) {
            throw new CryptoException("Invalid token: unable to get public key for " + domainName
                    + "." + serviceName + " service with keyid: " + keyId);
        }

        JWSVerifier verifier = JwtsHelper.getJWSVerifier(publicKey);
        if (!signedJWT.verify(verifier)) {
            throw new CryptoException("Unable to verify token signature");
        }
    }

    // our date values are stored in seconds

    long parseDateValue(Date date) {
        return date == null ? 0 : date.getTime() / 1000;
    }

    void setTokenFields() {

        setVersion(JwtsHelper.getIntegerClaim(claimsSet, CLAIM_VERSION, 0));
        setAudience(getClaimAudience());
        setExpiryTime(parseDateValue(claimsSet.getExpirationTime()));
        setIssueTime(parseDateValue(claimsSet.getIssueTime()));
        setNotBeforeTime(parseDateValue(claimsSet.getNotBeforeTime()));
        setAuthTime(JwtsHelper.getLongClaim(claimsSet, CLAIM_AUTH_TIME, 0));
        setIssuer(claimsSet.getIssuer());
        setSubject(claimsSet.getSubject());
        setJwtId(claimsSet.getJWTID());
    }

    String getClaimAudience() {
        List<String> audiences = claimsSet.getAudience();
        if (audiences != null && !audiences.isEmpty()) {
            return audiences.get(0);
        }
        return null;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public long getIssueTime() {
        return issueTime;
    }

    public void setIssueTime(long issueTime) {
        this.issueTime = issueTime;
    }

    public long getNotBeforeTime() {
        return notBeforeTime;
    }

    public void setNotBeforeTime(long notBeforeTime) {
        this.notBeforeTime = notBeforeTime;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public long getAuthTime() {
        return authTime;
    }

    public void setAuthTime(long authTime) {
        this.authTime = authTime;
    }

    public String getJwtId() {
        return jwtId;
    }

    public void setJwtId(String jwtId) {
        this.jwtId = jwtId;
    }

    public String getClientIdDomainName() {
        return clientIdDomainName;
    }

    public String getClientIdServiceName() {
        return clientIdServiceName;
    }
}
