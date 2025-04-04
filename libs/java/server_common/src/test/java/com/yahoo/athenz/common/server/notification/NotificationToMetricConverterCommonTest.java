/*
 *  Copyright The Athenz Authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.yahoo.athenz.common.server.notification;

import com.yahoo.rdl.Timestamp;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class NotificationToMetricConverterCommonTest {

    private final NotificationToMetricConverterCommon notificationToMetricConverterCommon = new NotificationToMetricConverterCommon();

    @Test
    public void testGetNumberOfDaysFromTimeStamp() {

        Timestamp currentTimeStamp = Timestamp.fromMillis(1601914761000L);
        Timestamp tomorrowTimeStamp = Timestamp.fromMillis(1602001164000L);
        Timestamp yesterdayTimeStamp = Timestamp.fromMillis(1601828361000L);
        Timestamp monthFromNowTimeStamp = Timestamp.fromMillis(1604594993000L);

        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(currentTimeStamp.toString(), currentTimeStamp.toString()), "0");
        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(currentTimeStamp.toString(), tomorrowTimeStamp.toString()), "1");
        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(currentTimeStamp.toString(), yesterdayTimeStamp.toString()), "-1");
        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(currentTimeStamp.toString(), monthFromNowTimeStamp.toString()), "31");
    }

    @Test
    public void testGetNumberOfDaysFromTimeStampInvalid() {
        Timestamp currentTimeStamp = Timestamp.fromMillis(1601914761000L);
        String badTimeStamp = "bad";

        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(currentTimeStamp.toString(), badTimeStamp), "");
        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(badTimeStamp, currentTimeStamp.toString()), "");
        assertEquals(notificationToMetricConverterCommon.getNumberOfDaysBetweenTimestamps(badTimeStamp, badTimeStamp), "");
    }
}
