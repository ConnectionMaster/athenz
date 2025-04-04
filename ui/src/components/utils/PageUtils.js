/*
 * Copyright 2021 Verizon Media
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
class PageUtils {
    static homePage() {
        return `/`;
    }
    static rolePage(domain) {
        return `/domain/${domain}/role`;
    }
    static groupPage(domain) {
        return `/domain/${domain}/group`;
    }
    static createDomainPage() {
        return `/domain/create`;
    }
    static manageDomainPage() {
        return `/domain/manage`;
    }
    static servicePage(domain) {
        return `/domain/${domain}/service`;
    }
    static workflowAdminPage() {
        return `/workflow/admin`;
    }
    static workflowDomainPage() {
        return `/workflow/domain`;
    }
    static workflowRoleReviewPage() {
        return `/workflow/role`;
    }
    static workflowGroupReviewPage() {
        return `/workflow/group`;
    }
}

// opens new tab on cmd + click or ctrl + click
export const onClickNewTabFunction = (route, router, args) => {
    if (args.metaKey || args.ctrlKey) {
        args.view.open(
            args.view.origin + route,
            '_blank',
            'noopener,norefferer'
        );
    } else {
        router.push(route, route);
    }
};

export default PageUtils;
