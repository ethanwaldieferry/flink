/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.operators.windowing;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.streaming.api.windowing.assigners.DynamicEventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.assigners.MergingWindowAssigner;
import org.apache.flink.streaming.api.windowing.assigners.SessionWindowTimeGapExtractor;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.EventTimeTrigger;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.TestLogger;

import org.apache.flink.shaded.guava32.com.google.common.collect.Lists;

import org.junit.Test;
import org.mockito.Matchers;

import java.util.Collection;

import static org.apache.flink.streaming.util.StreamRecordMatchers.timeWindow;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyCollection;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

/** Tests for {@link EventTimeSessionWindows}. */
public class EventTimeSessionWindowsTest extends TestLogger {

    @Test
    public void testWindowAssignment() {
        final int sessionGap = 5000;

        WindowAssigner.WindowAssignerContext mockContext =
                mock(WindowAssigner.WindowAssignerContext.class);

        EventTimeSessionWindows assigner =
                EventTimeSessionWindows.withGap(Time.milliseconds(sessionGap));

        assertThat(
                assigner.assignWindows("String", 0L, mockContext),
                contains(timeWindow(0, 0 + sessionGap)));
        assertThat(
                assigner.assignWindows("String", 4999L, mockContext),
                contains(timeWindow(4999, 4999 + sessionGap)));
        assertThat(
                assigner.assignWindows("String", 5000L, mockContext),
                contains(timeWindow(5000, 5000 + sessionGap)));
    }

    @Test
    public void testMergeSinglePointWindow() {
        MergingWindowAssigner.MergeCallback callback =
                mock(MergingWindowAssigner.MergeCallback.class);

        EventTimeSessionWindows assigner = EventTimeSessionWindows.withGap(Time.milliseconds(5000));

        assigner.mergeWindows(Lists.newArrayList(new TimeWindow(0, 0)), callback);

        verify(callback, never()).merge(anyCollection(), Matchers.anyObject());
    }

    @Test
    public void testMergeSingleWindow() {
        MergingWindowAssigner.MergeCallback callback =
                mock(MergingWindowAssigner.MergeCallback.class);

        EventTimeSessionWindows assigner = EventTimeSessionWindows.withGap(Time.milliseconds(5000));

        assigner.mergeWindows(Lists.newArrayList(new TimeWindow(0, 1)), callback);

        verify(callback, never()).merge(anyCollection(), Matchers.anyObject());
    }

    @Test
    public void testMergeConsecutiveWindows() {
        MergingWindowAssigner.MergeCallback callback =
                mock(MergingWindowAssigner.MergeCallback.class);

        EventTimeSessionWindows assigner = EventTimeSessionWindows.withGap(Time.milliseconds(5000));

        assigner.mergeWindows(
                Lists.newArrayList(
                        new TimeWindow(0, 1),
                        new TimeWindow(1, 2),
                        new TimeWindow(2, 3),
                        new TimeWindow(4, 5),
                        new TimeWindow(5, 6)),
                callback);

        verify(callback, times(1))
                .merge(
                        (Collection<TimeWindow>)
                                argThat(
                                        containsInAnyOrder(
                                                new TimeWindow(0, 1),
                                                new TimeWindow(1, 2),
                                                new TimeWindow(2, 3))),
                        eq(new TimeWindow(0, 3)));

        verify(callback, times(1))
                .merge(
                        (Collection<TimeWindow>)
                                argThat(
                                        containsInAnyOrder(
                                                new TimeWindow(4, 5), new TimeWindow(5, 6))),
                        eq(new TimeWindow(4, 6)));

        verify(callback, times(2)).merge(anyCollection(), Matchers.anyObject());
    }

    @Test
    public void testMergeCoveringWindow() {
        MergingWindowAssigner.MergeCallback callback =
                mock(MergingWindowAssigner.MergeCallback.class);

        EventTimeSessionWindows assigner = EventTimeSessionWindows.withGap(Time.milliseconds(5000));

        assigner.mergeWindows(
                Lists.newArrayList(
                        new TimeWindow(1, 1),
                        new TimeWindow(0, 2),
                        new TimeWindow(4, 7),
                        new TimeWindow(5, 6)),
                callback);

        verify(callback, times(1))
                .merge(
                        (Collection<TimeWindow>)
                                argThat(
                                        containsInAnyOrder(
                                                new TimeWindow(1, 1), new TimeWindow(0, 2))),
                        eq(new TimeWindow(0, 2)));

        verify(callback, times(1))
                .merge(
                        (Collection<TimeWindow>)
                                argThat(
                                        containsInAnyOrder(
                                                new TimeWindow(5, 6), new TimeWindow(4, 7))),
                        eq(new TimeWindow(4, 7)));

        verify(callback, times(2)).merge(anyCollection(), Matchers.anyObject());
    }

    @Test
    public void testTimeUnits() {
        // sanity check with one other time unit

        final int sessionGap = 5000;

        WindowAssigner.WindowAssignerContext mockContext =
                mock(WindowAssigner.WindowAssignerContext.class);

        EventTimeSessionWindows assigner =
                EventTimeSessionWindows.withGap(Time.seconds(sessionGap / 1000));

        assertThat(
                assigner.assignWindows("String", 0L, mockContext),
                contains(timeWindow(0, 0 + sessionGap)));
        assertThat(
                assigner.assignWindows("String", 4999L, mockContext),
                contains(timeWindow(4999, 4999 + sessionGap)));
        assertThat(
                assigner.assignWindows("String", 5000L, mockContext),
                contains(timeWindow(5000, 5000 + sessionGap)));
    }

    @Test
    public void testInvalidParameters() {
        try {
            EventTimeSessionWindows.withGap(Time.seconds(-1));
            fail("should fail");
        } catch (IllegalArgumentException e) {
            assertThat(e.toString(), containsString("0 < size"));
        }

        try {
            EventTimeSessionWindows.withGap(Time.seconds(0));
            fail("should fail");
        } catch (IllegalArgumentException e) {
            assertThat(e.toString(), containsString("0 < size"));
        }
    }

    @Test
    public void testProperties() {
        EventTimeSessionWindows assigner = EventTimeSessionWindows.withGap(Time.seconds(5));

        assertTrue(assigner.isEventTime());
        assertEquals(
                new TimeWindow.Serializer(), assigner.getWindowSerializer(new ExecutionConfig()));
        assertThat(assigner.getDefaultTrigger(), instanceOf(EventTimeTrigger.class));
    }

    @Test
    public void testDynamicGapProperties() {
        SessionWindowTimeGapExtractor<String> extractor = mock(SessionWindowTimeGapExtractor.class);
        DynamicEventTimeSessionWindows<String> assigner =
                EventTimeSessionWindows.withDynamicGap(extractor);

        assertNotNull(assigner);
        assertTrue(assigner.isEventTime());
    }
}
