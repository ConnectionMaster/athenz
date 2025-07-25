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

package com.yahoo.athenz.zms.notification;

import com.yahoo.athenz.auth.util.AthenzUtils;
import com.yahoo.athenz.common.ServerCommonConsts;
import com.yahoo.athenz.common.server.notification.*;
import com.yahoo.athenz.common.server.util.ResourceUtils;
import com.yahoo.athenz.zms.*;
import com.yahoo.athenz.zms.utils.ZMSUtils;
import com.yahoo.rdl.Timestamp;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.yahoo.athenz.common.ServerCommonConsts.ADMIN_ROLE_NAME;
import static com.yahoo.athenz.common.server.notification.NotificationServiceConstants.*;
import static com.yahoo.athenz.common.server.notification.impl.MetricNotificationService.*;

public class GroupMemberExpiryNotificationTask implements NotificationTask {

    private final DBService dbService;
    private final String userDomainPrefix;
    private final NotificationCommon notificationCommon;
    private final DomainRoleMembersFetcher domainRoleMembersFetcher;
    private final DomainMetaFetcher domainMetaFetcher;
    private static final Logger LOGGER = LoggerFactory.getLogger(GroupMemberExpiryNotificationTask.class);
    private final static String DESCRIPTION = "group membership expiration reminders";
    private final GroupExpiryDomainNotificationToEmailConverter groupExpiryDomainNotificationToEmailConverter;
    private final GroupExpiryPrincipalNotificationToEmailConverter groupExpiryPrincipalNotificationToEmailConverter;
    private final GroupExpiryDomainNotificationToMetricConverter groupExpiryDomainNotificationToMetricConverter;
    private final GroupExpiryPrincipalNotificationToToMetricConverter groupExpiryPrincipalNotificationToToMetricConverter;
    private final GroupExpiryPrincipalNotificationToSlackConverter groupExpiryPrincipalNotificationToSlackConverter;
    private final GroupExpiryDomainNotificationToSlackConverter groupExpiryDomainNotificationToSlackConverter;

    private final static String[] TEMPLATE_COLUMN_NAMES = { "DOMAIN", "GROUP", "MEMBER", "EXPIRATION", "NOTES" };

    public GroupMemberExpiryNotificationTask(DBService dbService, String userDomainPrefix,
                                             NotificationConverterCommon notificationConverterCommon) {

        this.dbService = dbService;
        this.userDomainPrefix = userDomainPrefix;
        this.domainRoleMembersFetcher = new DomainRoleMembersFetcher(dbService, userDomainPrefix);
        this.domainMetaFetcher = new DomainMetaFetcher(dbService);
        this.notificationCommon = new NotificationCommon(domainRoleMembersFetcher, userDomainPrefix, domainMetaFetcher);
        this.groupExpiryPrincipalNotificationToEmailConverter =
                new GroupExpiryPrincipalNotificationToEmailConverter(notificationConverterCommon);
        this.groupExpiryDomainNotificationToEmailConverter =
                new GroupExpiryDomainNotificationToEmailConverter(notificationConverterCommon);
        this.groupExpiryPrincipalNotificationToToMetricConverter
                = new GroupExpiryPrincipalNotificationToToMetricConverter();
        this.groupExpiryDomainNotificationToMetricConverter = new GroupExpiryDomainNotificationToMetricConverter();
        this.groupExpiryPrincipalNotificationToSlackConverter = new GroupExpiryPrincipalNotificationToSlackConverter(notificationConverterCommon);
        this.groupExpiryDomainNotificationToSlackConverter = new GroupExpiryDomainNotificationToSlackConverter(notificationConverterCommon);
    }

    @Override
    public List<Notification> getNotifications() {
        return getNotifications(null);
    }

    @Override
    public List<Notification> getNotifications(NotificationObjectStore notificationObjectStore) {
        Map<String, DomainGroupMember> expiryMembers = dbService.getGroupExpiryMembers(1);
        if (expiryMembers == null || expiryMembers.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("No expiry group members available to send notifications");
            }
            return new ArrayList<>();
        }

        List<Notification> notificationDetails = getNotificationDetails(
                expiryMembers, Notification.ConsolidatedBy.PRINCIPAL,
                groupExpiryPrincipalNotificationToEmailConverter,
                groupExpiryDomainNotificationToEmailConverter,
                groupExpiryPrincipalNotificationToToMetricConverter,
                groupExpiryDomainNotificationToMetricConverter,
                groupExpiryPrincipalNotificationToSlackConverter,
                groupExpiryDomainNotificationToSlackConverter,
                notificationObjectStore);

        notificationDetails.addAll(
                getNotificationDetails(
                        expiryMembers, Notification.ConsolidatedBy.DOMAIN,
                        groupExpiryPrincipalNotificationToEmailConverter,
                        groupExpiryDomainNotificationToEmailConverter,
                        groupExpiryPrincipalNotificationToToMetricConverter,
                        groupExpiryDomainNotificationToMetricConverter,
                        groupExpiryPrincipalNotificationToSlackConverter,
                        groupExpiryDomainNotificationToSlackConverter,
                        notificationObjectStore)
        );
        return notificationCommon.printNotificationDetailsToLog(notificationDetails, DESCRIPTION);
    }

    public StringBuilder getDetailString(GroupMember memberGroup) {
        StringBuilder detailsRow = new StringBuilder(256);
        detailsRow.append(memberGroup.getDomainName()).append(';');
        detailsRow.append(memberGroup.getGroupName()).append(';');
        detailsRow.append(memberGroup.getMemberName()).append(';');
        detailsRow.append(memberGroup.getExpiration()).append(';');
        detailsRow.append(memberGroup.getNotifyDetails() == null ?
                "" : URLEncoder.encode(memberGroup.getNotifyDetails(), StandardCharsets.UTF_8));
        return detailsRow;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    private Map<String, String> processGroupReminder(Map<String, List<GroupMember>> domainAdminMap,
                                                     DomainGroupMember member) {

        Map<String, String> details = new HashMap<>();

        // each principal can have multiple groups in multiple domains that
        // it's part of thus multiple possible entries.
        // we're going to collect them into one string and separate
        // with | between those. The format will be:
        // memberGroupsDetails := <group-member-entry>[|<group-member-entry]*
        // group-member-entry := <domain-name>;<group-name>;<member-name>;<expiration>

        final List<GroupMember> memberGroups = member.getMemberGroups();
        if (ZMSUtils.isCollectionEmpty(memberGroups)) {
            return details;
        }

        StringBuilder memberGroupsDetails = new StringBuilder(256);
        for (GroupMember memberGroup : memberGroups) {
            EnumSet<DisableNotificationEnum> disabledNotificationState = getDisabledNotificationState(memberGroup);
            if (disabledNotificationState.containsAll(Arrays.asList(DisableNotificationEnum.ADMIN, DisableNotificationEnum.USER))) {
                LOGGER.info("Notification disabled for group {}, domain {}", memberGroup.getGroupName(), memberGroup.getDomainName());
                continue;
            }

            // check to see if the administrator has configured to generate notifications
            // only for members that are expiring in less than a week

            if (disabledNotificationState.contains(DisableNotificationEnum.OVER_ONE_WEEK)) {
                Timestamp notificationTimestamp = memberGroup.getExpiration();
                if (notificationTimestamp == null || notificationTimestamp.millis() - System.currentTimeMillis() > NotificationUtils.WEEK_EXPIRY_CHECK) {
                    LOGGER.info("Notification skipped for group {}, domain {}, notification date is more than a week way",
                            memberGroup.getGroupName(), memberGroup.getDomainName());
                    continue;
                }
            }

            final String domainName = memberGroup.getDomainName();

            // first we're going to update our expiry details string

            if (!disabledNotificationState.contains(DisableNotificationEnum.USER)) {
                if (memberGroupsDetails.length() != 0) {
                    memberGroupsDetails.append('|');
                }
                memberGroupsDetails.append(getDetailString(memberGroup));
            }

            // next we're going to update our domain admin map

            if (!disabledNotificationState.contains(DisableNotificationEnum.ADMIN)) {
                addDomainGroupMember(domainAdminMap, domainName, memberGroup);
            }
        }
        if (memberGroupsDetails.length() > 0) {
            details.put(NOTIFICATION_DETAILS_ROLES_LIST, memberGroupsDetails.toString());
            details.put(NOTIFICATION_DETAILS_MEMBER, member.getMemberName());
        }

        return details;
    }

    private void addDomainGroupMember(Map<String, List<GroupMember>> domainAdminMap, final String domainName,
                                      GroupMember memberGroup) {

        List<GroupMember> domainGroupMembers = domainAdminMap.computeIfAbsent(domainName, k -> new ArrayList<>());

        // make sure we don't have any duplicates

        for (GroupMember group : domainGroupMembers) {
            if (group.getGroupName().equals(memberGroup.getGroupName())
                    && group.getMemberName().equals(memberGroup.getMemberName())) {
                return;
            }
        }
        domainGroupMembers.add(memberGroup);
    }

    EnumSet<DisableNotificationEnum> getDisabledNotificationState(GroupMember memberGroup) {

        Group group = dbService.getGroup(memberGroup.getDomainName(), memberGroup.getGroupName(), false, false);
        try {
            // for groups, we're going to check the disabled expiration notification tag, and
            // if it's not set, we're going to honor the disabled reminder notification tag

            EnumSet<DisableNotificationEnum> enumSet = DisableNotificationEnum.getDisabledNotificationState(
                    group, Group::getTags, ZMSConsts.DISABLE_EXPIRATION_NOTIFICATIONS_TAG);
            if (enumSet.isEmpty()) {
                enumSet = DisableNotificationEnum.getDisabledNotificationState(group, Group::getTags,
                        ZMSConsts.DISABLE_REMINDER_NOTIFICATIONS_TAG);
            }
            return enumSet;
        } catch (NumberFormatException ex) {
            LOGGER.error("Invalid mask value for {}/{} tags in domain {}, group {}",
                    ZMSConsts.DISABLE_EXPIRATION_NOTIFICATIONS_TAG, ZMSConsts.DISABLE_REMINDER_NOTIFICATIONS_TAG,
                    memberGroup.getDomainName(), memberGroup.getGroupName());
        }

        return DisableNotificationEnum.getEnumSet(0);
    }

    Map<String, String> processMemberReminder(List<GroupMember> memberGroups) {

        Map<String, String> details = new HashMap<>();

        // each domain can have multiple members that are about
        // to expire to we're going to collect them into one
        // string and separate with | between those. The format will be:
        // memberDetails := <member-entry>[|<member-entry]*
        // member-entry := <member-name>;<group-name>;<expiration>

        if (ZMSUtils.isCollectionEmpty(memberGroups)) {
            return details;
        }

        StringBuilder memberDetails = new StringBuilder(256);
        for (GroupMember memberGroup : memberGroups) {

            // first we're going to update our expiry details string

            if (memberDetails.length() != 0) {
                memberDetails.append('|');
            }
            memberDetails.append(getDetailString(memberGroup));
        }

        details.put(NOTIFICATION_DETAILS_MEMBERS_LIST, memberDetails.toString());
        return details;
    }

    List<Notification> getNotificationDetails(Map<String, DomainGroupMember> members,
              Notification.ConsolidatedBy consolidatedBy,
              NotificationToEmailConverter principalNotificationToEmailConverter,
              NotificationToEmailConverter domainAdminNotificationToEmailConverter,
              NotificationToMetricConverter principalNotificationToMetricConverter,
              NotificationToMetricConverter domainAdminNotificationToMetricConverter,
              NotificationToSlackMessageConverter principalNotificationToSlackConverter,
              NotificationToSlackMessageConverter domainAdminNotificationToSlackConverter,
              NotificationObjectStore notificationObjectStore) {

        // our members map contains three two of entries:
        //  1. human user: user.john-doe -> { expiring-roles }
        //  2. service-identity: athenz.api -> { expiring-roles }
        // So for service-identity accounts - we need to extract the list
        // of human domain admins and combine them with human users so the
        // human users gets only a single notification.

        Map<String, DomainGroupMember> consolidatedMembers = new HashMap<>();
        if (Notification.ConsolidatedBy.PRINCIPAL.equals(consolidatedBy)) {
            consolidatedMembers = consolidateGroupMembers(members);
        } else if (Notification.ConsolidatedBy.DOMAIN.equals(consolidatedBy)) {
            consolidatedMembers = consolidateGroupMembersByDomain(members);
        }

        List<Notification> notificationList = new ArrayList<>();
        Map<String, List<GroupMember>> domainAdminMap = new HashMap<>();

        for (String principal : consolidatedMembers.keySet()) {

            // we're going to process the role member, update
            // our domain admin map accordingly and return
            // the details object that we need to send to the
            // notification agent for processing

            Map<String, String> details = processGroupReminder(domainAdminMap, consolidatedMembers.get(principal));
            if (!details.isEmpty()) {
                Notification notification = notificationCommon.createNotification(
                        Notification.Type.GROUP_MEMBER_EXPIRY,
                        consolidatedBy,
                        principal, details, principalNotificationToEmailConverter,
                        principalNotificationToMetricConverter,
                        principalNotificationToSlackConverter);
                if (notification != null) {
                    notificationList.add(notification);
                }
            }
        }

        // now we're going to send reminders to all the domain administrators

        Map<String, DomainGroupMember> consolidatedDomainAdmins = new HashMap<>();
        if (Notification.ConsolidatedBy.PRINCIPAL.equals(consolidatedBy)) {
            consolidatedDomainAdmins = consolidateDomainAdmins(domainAdminMap);
        } else if (Notification.ConsolidatedBy.DOMAIN.equals(consolidatedBy)) {
            consolidatedDomainAdmins = consolidateDomainAdminsByDomain(domainAdminMap);
        }

        for (String principal : consolidatedDomainAdmins.keySet()) {

            List<GroupMember> groupMembers = consolidatedDomainAdmins.get(principal).getMemberGroups();
            Map<String, String> details = processMemberReminder(groupMembers);
            Notification notification = notificationCommon.createNotification(
                    Notification.Type.GROUP_MEMBER_EXPIRY,
                    consolidatedBy,
                    principal, details, domainAdminNotificationToEmailConverter,
                    domainAdminNotificationToMetricConverter,
                    domainAdminNotificationToSlackConverter);
            if (notification != null) {
                notificationList.add(notification);
                registerNotificationObjects(notificationObjectStore, consolidatedBy, principal, groupMembers);
            }
        }

        return notificationList;
    }

    void registerNotificationObjects(NotificationObjectStore notificationObjectStore,
            Notification.ConsolidatedBy consolidatedBy, final String principal, List<GroupMember> groupMembers) {

        if (notificationObjectStore == null) {
            return;
        }

        // notification object store is only used for consolidated notifications by principal

        if (!Notification.ConsolidatedBy.PRINCIPAL.equals(consolidatedBy)) {
            return;
        }

        // ignore any non-user principals

        if (!principal.startsWith(userDomainPrefix)) {
            return;
        }

        Set<String> reviewObjectArns = new HashSet<>();
        for (GroupMember groupMember : groupMembers) {
            reviewObjectArns.add(ResourceUtils.groupResourceName(groupMember.getDomainName(), groupMember.getGroupName()));
        }
        try {
            notificationObjectStore.registerReviewObjects(principal, new ArrayList<>(reviewObjectArns));
        } catch (Exception ex) {
            LOGGER.error("unable to register review group objects for principal: {}: {}", principal, ex.getMessage());
        }
    }

    Map<String, DomainGroupMember> consolidateGroupMembers(Map<String, DomainGroupMember> members) {

        Map<String, DomainGroupMember> consolidatedMembers = new HashMap<>();

        // iterate through each principal. if the principal is:
        // user -> as the roles to the list
        // service -> lookup domain admins for the service and add to the individual human users only

        for (String principal : members.keySet()) {

            final String domainName = AthenzUtils.extractPrincipalDomainName(principal);
            if (userDomainPrefix.equals(domainName + ".")) {
                addGroupMembers(principal, consolidatedMembers, members.get(principal).getMemberGroups());
            } else {
                // domain role fetcher only returns the human users

                Set<String> domainAdminMembers = domainRoleMembersFetcher.getDomainRoleMembers(domainName, ADMIN_ROLE_NAME);
                if (ZMSUtils.isCollectionEmpty(domainAdminMembers)) {
                    continue;
                }
                for (String domainAdminMember : domainAdminMembers) {
                    addGroupMembers(domainAdminMember, consolidatedMembers, members.get(principal).getMemberGroups());
                }
            }
        }

        return consolidatedMembers;
    }

    Map<String, DomainGroupMember> consolidateGroupMembersByDomain(Map<String, DomainGroupMember> members) {

        Map<String, DomainGroupMember> consolidatedMembers = new HashMap<>();

        // iterate through each principal. if the principal is:
        // user -> as the roles to the list
        // service -> add the roles to domain name of the svc

        for (String principal : members.keySet()) {

            final String domainName = AthenzUtils.extractPrincipalDomainName(principal);
            if (userDomainPrefix.equals(domainName + ".")) {
                addGroupMembers(principal, consolidatedMembers, members.get(principal).getMemberGroups());
            } else {
                addGroupMembers(domainName, consolidatedMembers, members.get(principal).getMemberGroups());
            }
        }

        return consolidatedMembers;
    }

    Map<String, DomainGroupMember> consolidateDomainAdmins(Map<String, List<GroupMember>> domainGroupMembers) {

        Map<String, DomainGroupMember> consolidatedDomainAdmins = new HashMap<>();

        // iterate through each domain and the groups within each domain.
        // if the group does not have the notify roles setup, then we'll
        // add the notifications to the domain admins otherwise we'll
        // add it to the configured notify roles members only

        for (String domainName : domainGroupMembers.keySet()) {

            List<GroupMember> groupMemberList = domainGroupMembers.get(domainName);
            if (ZMSUtils.isCollectionEmpty(groupMemberList)) {
                continue;
            }

            // domain role fetcher only returns the human users

            Set<String> domainAdminMembers = domainRoleMembersFetcher.getDomainRoleMembers(domainName, ADMIN_ROLE_NAME);

            for (GroupMember groupMember : groupMemberList) {

                // if we have a notify-roles configured then we're going to
                // extract the list of members from those roles, otherwise
                // we're going to use the domain admin members

                Set<String> groupAdminMembers;
                if (!StringUtil.isEmpty(groupMember.getNotifyRoles())) {
                    groupAdminMembers = NotificationUtils.extractNotifyRoleMembers(domainRoleMembersFetcher,
                            groupMember.getDomainName(), groupMember.getNotifyRoles());
                } else {
                    groupAdminMembers = domainAdminMembers;
                }

                if (ZMSUtils.isCollectionEmpty(groupAdminMembers)) {
                    continue;
                }
                for (String groupAdminMember : groupAdminMembers) {
                    addGroupMembers(groupAdminMember, consolidatedDomainAdmins, Collections.singletonList(groupMember));
                }
            }
        }

        return consolidatedDomainAdmins;
    }

    Map<String, DomainGroupMember> consolidateDomainAdminsByDomain(Map<String, List<GroupMember>> domainGroupMembers) {

        Map<String, DomainGroupMember> consolidatedDomainAdmins = new HashMap<>();

        // iterate through each domain and the groups within each domain.
        // if the group does not have the notify roles setup, then we'll
        // add the notifications to the domain otherwise we'll
        // add it to the configured notify roles members only

        for (String domainName : domainGroupMembers.keySet()) {

            List<GroupMember> groupMemberList = domainGroupMembers.get(domainName);
            if (ZMSUtils.isCollectionEmpty(groupMemberList)) {
                continue;
            }

            Set<String> notifyMember = Collections.singleton(domainName);

            for (GroupMember groupMember : groupMemberList) {

                // if we have a notify-roles configured then we're going to
                // extract the list of members from those roles, otherwise
                // we're going to use the domain name

                Set<String> groupNotifyMembers;
                if (!StringUtil.isEmpty(groupMember.getNotifyRoles())) {
                    groupNotifyMembers = NotificationUtils.extractNotifyRoleMembers(domainRoleMembersFetcher,
                            groupMember.getDomainName(), groupMember.getNotifyRoles());
                } else {
                    groupNotifyMembers = notifyMember;
                }

                if (ZMSUtils.isCollectionEmpty(groupNotifyMembers)) {
                    continue;
                }
                for (String groupNotifyMember : groupNotifyMembers) {
                    addGroupMembers(groupNotifyMember, consolidatedDomainAdmins, Collections.singletonList(groupMember));
                }
            }
        }

        return consolidatedDomainAdmins;
    }

    void addGroupMembers(final String consolidatedPrincipal, Map<String, DomainGroupMember> consolidatedMembers,
                         List<GroupMember> groupMemberList) {
        DomainGroupMember groupMembers = consolidatedMembers.computeIfAbsent(consolidatedPrincipal,
                k -> new DomainGroupMember().setMemberName(consolidatedPrincipal).setMemberGroups(new ArrayList<>()));
        if (!ZMSUtils.isCollectionEmpty(groupMemberList)) {
            groupMembers.getMemberGroups().addAll(groupMemberList);
        }
    }

    public static class GroupExpiryPrincipalNotificationToEmailConverter implements NotificationToEmailConverter {
        private static final String EMAIL_TEMPLATE_PRINCIPAL_EXPIRY = "messages/group-member-expiry.html";
        private static final String PRINCIPAL_EXPIRY_SUBJECT = "athenz.notification.email.group_member.expiry.subject";

        private final NotificationConverterCommon notificationConverterCommon;
        private final String emailPrincipalExpiryBody;

        public GroupExpiryPrincipalNotificationToEmailConverter(NotificationConverterCommon notificationConverterCommon) {
            this.notificationConverterCommon = notificationConverterCommon;
            emailPrincipalExpiryBody =  notificationConverterCommon.readContentFromFile(getClass().getClassLoader(), EMAIL_TEMPLATE_PRINCIPAL_EXPIRY);
        }

        private String getPrincipalExpiryBody(Map<String, String> metaDetails) {
            if (metaDetails == null) {
                return null;
            }

            return notificationConverterCommon.generateBodyFromTemplate(metaDetails, emailPrincipalExpiryBody,
                    NOTIFICATION_DETAILS_MEMBER, NOTIFICATION_DETAILS_ROLES_LIST,
                    TEMPLATE_COLUMN_NAMES.length, TEMPLATE_COLUMN_NAMES);
        }

        @Override
        public NotificationEmail getNotificationAsEmail(Notification notification) {
            String subject = notificationConverterCommon.getSubject(PRINCIPAL_EXPIRY_SUBJECT);
            String body = getPrincipalExpiryBody(notification.getDetails());
            Set<String> fullyQualifiedEmailAddresses = notificationConverterCommon.getFullyQualifiedEmailAddresses(notification.getRecipients());
            return new NotificationEmail(subject, body, fullyQualifiedEmailAddresses);
        }
    }

    public static class GroupExpiryDomainNotificationToEmailConverter implements NotificationToEmailConverter {
        private static final String EMAIL_TEMPLATE_DOMAIN_MEMBER_EXPIRY = "messages/domain-group-member-expiry.html";
        private static final String DOMAIN_MEMBER_EXPIRY_SUBJECT = "athenz.notification.email.domain.group_member.expiry.subject";

        private final NotificationConverterCommon notificationConverterCommon;
        private final String emailDomainMemberExpiryBody;

        public GroupExpiryDomainNotificationToEmailConverter(NotificationConverterCommon notificationConverterCommon) {
            this.notificationConverterCommon = notificationConverterCommon;
            emailDomainMemberExpiryBody = notificationConverterCommon.readContentFromFile(getClass().getClassLoader(), EMAIL_TEMPLATE_DOMAIN_MEMBER_EXPIRY);
        }

        private String getDomainMemberExpiryBody(Map<String, String> metaDetails) {
            if (metaDetails == null) {
                return null;
            }

            return notificationConverterCommon.generateBodyFromTemplate(metaDetails, emailDomainMemberExpiryBody,
                    NOTIFICATION_DETAILS_DOMAIN, NOTIFICATION_DETAILS_MEMBERS_LIST,
                    TEMPLATE_COLUMN_NAMES.length, TEMPLATE_COLUMN_NAMES);
        }

        @Override
        public NotificationEmail getNotificationAsEmail(Notification notification) {
            String subject = notificationConverterCommon.getSubject(DOMAIN_MEMBER_EXPIRY_SUBJECT);
            String body = getDomainMemberExpiryBody(notification.getDetails());
            Set<String> fullyQualifiedEmailAddresses = notificationConverterCommon.getFullyQualifiedEmailAddresses(notification.getRecipients());
            return new NotificationEmail(subject, body, fullyQualifiedEmailAddresses);
        }
    }

    public static class GroupExpiryPrincipalNotificationToToMetricConverter implements NotificationToMetricConverter {
        private final static String NOTIFICATION_TYPE = "principal_group_membership_expiry";
        private final NotificationToMetricConverterCommon notificationToMetricConverterCommon = new NotificationToMetricConverterCommon();

        @Override
        public NotificationMetric getNotificationAsMetrics(Notification notification, Timestamp currentTime) {

            return NotificationUtils.getNotificationAsMetrics(notification, currentTime, NOTIFICATION_TYPE,
                    NOTIFICATION_DETAILS_ROLES_LIST, METRIC_NOTIFICATION_GROUP_KEY, METRIC_NOTIFICATION_EXPIRY_DAYS_KEY,
                    notificationToMetricConverterCommon);
        }
    }

    public static class GroupExpiryDomainNotificationToMetricConverter implements NotificationToMetricConverter {
        private final static String NOTIFICATION_TYPE = "domain_group_membership_expiry";
        private final NotificationToMetricConverterCommon notificationToMetricConverterCommon = new NotificationToMetricConverterCommon();

        @Override
        public NotificationMetric getNotificationAsMetrics(Notification notification, Timestamp currentTime) {

            return NotificationUtils.getNotificationAsMetrics(notification, currentTime, NOTIFICATION_TYPE,
                    NOTIFICATION_DETAILS_MEMBERS_LIST, METRIC_NOTIFICATION_GROUP_KEY, METRIC_NOTIFICATION_EXPIRY_DAYS_KEY,
                    notificationToMetricConverterCommon);
        }
    }

    public static class GroupExpiryPrincipalNotificationToSlackConverter implements NotificationToSlackMessageConverter {
        private static final String SLACK_TEMPLATE_PRINCIPAL_MEMBER_EXPIRY = "messages/slack-group-member-expiry.ftl";
        private final NotificationConverterCommon notificationConverterCommon;
        private final String slackPrincipalExpiryTemplate;

        public GroupExpiryPrincipalNotificationToSlackConverter(NotificationConverterCommon notificationConverterCommon) {
            this.notificationConverterCommon = notificationConverterCommon;
            slackPrincipalExpiryTemplate = notificationConverterCommon.readContentFromFile(
                    getClass().getClassLoader(), SLACK_TEMPLATE_PRINCIPAL_MEMBER_EXPIRY);
        }

        @Override
        public NotificationSlackMessage getNotificationAsSlackMessage(Notification notification) {
            String slackMessageContent = notificationConverterCommon.getSlackMessageFromTemplate(notification.getDetails(), slackPrincipalExpiryTemplate, NOTIFICATION_DETAILS_ROLES_LIST, TEMPLATE_COLUMN_NAMES.length, ServerCommonConsts.OBJECT_GROUP);

            Set<String> slackRecipients = notificationConverterCommon.getSlackRecipients(notification.getRecipients(), notification.getNotificationDomainMeta());
            return new NotificationSlackMessage(
                    slackMessageContent,
                    slackRecipients);
        }
    }

    public static class GroupExpiryDomainNotificationToSlackConverter implements NotificationToSlackMessageConverter {

        private static final String SLACK_TEMPLATE_DOMAIN_MEMBER_EXPIRY = "messages/slack-domain-group-member-expiry.ftl";
        private final NotificationConverterCommon notificationConverterCommon;
        private final String slackDomainExpiryTemplate;

        public GroupExpiryDomainNotificationToSlackConverter(NotificationConverterCommon notificationConverterCommon) {
            this.notificationConverterCommon = notificationConverterCommon;
            slackDomainExpiryTemplate = notificationConverterCommon.readContentFromFile(
                    getClass().getClassLoader(), SLACK_TEMPLATE_DOMAIN_MEMBER_EXPIRY);
        }

        @Override
        public NotificationSlackMessage getNotificationAsSlackMessage(Notification notification) {
            String slackMessageContent = notificationConverterCommon.getSlackMessageFromTemplate(notification.getDetails(), slackDomainExpiryTemplate, NOTIFICATION_DETAILS_MEMBERS_LIST, TEMPLATE_COLUMN_NAMES.length, ServerCommonConsts.OBJECT_GROUP);
            if (StringUtil.isEmpty(slackMessageContent)) {
                return null;
            }
            Set<String> slackRecipients = notificationConverterCommon.getSlackRecipients(notification.getRecipients(), notification.getNotificationDomainMeta());
            return new NotificationSlackMessage(
                    slackMessageContent,
                    slackRecipients);
        }
    }
}
