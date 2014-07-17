/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import uk.co.real_logic.aeron.conductor.*;
import uk.co.real_logic.aeron.util.TermHelper;
import uk.co.real_logic.aeron.util.command.LogBuffersMessageFlyweight;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.broadcast.BroadcastBufferDescriptor;
import uk.co.real_logic.aeron.util.concurrent.broadcast.BroadcastReceiver;
import uk.co.real_logic.aeron.util.concurrent.broadcast.BroadcastTransmitter;
import uk.co.real_logic.aeron.util.concurrent.broadcast.CopyBroadcastReceiver;
import uk.co.real_logic.aeron.util.protocol.ErrorFlyweight;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

import static uk.co.real_logic.aeron.util.ErrorCode.INVALID_DESTINATION_IN_PUBLICATION;
import static uk.co.real_logic.aeron.util.ErrorCode.PUBLICATION_CHANNEL_ALREADY_EXISTS;
import static uk.co.real_logic.aeron.util.command.ControlProtocolEvents.ON_NEW_PUBLICATION;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.LogBufferDescriptor.STATE_BUFFER_LENGTH;

public class ClientConductorTest extends MockBufferUsage
{
    public static final int COUNTER_BUFFER_SZ = 1024;

    public static final String DESTINATION = "udp://localhost:40124";
    public static final long CHANNEL_ID_1 = 2L;
    public static final long CHANNEL_ID_2 = 4L;
    public static final long TERM_ID_1 = 1L;
    public static final int SEND_BUFFER_CAPACITY = 1024;

    public static final int BROADCAST_BUFFER_SZ = (16 * 1024) + BroadcastBufferDescriptor.TRAILER_LENGTH;
    public static final long CORRELATION_ID = 2000;
    public static final int AWAIT_TIMEOUT = 100;

    private final LogBuffersMessageFlyweight newBufferMessage = new LogBuffersMessageFlyweight();
    private final ErrorFlyweight errorHeader = new ErrorFlyweight();

    private final ByteBuffer sendBuffer = ByteBuffer.allocate(SEND_BUFFER_CAPACITY);
    private final AtomicBuffer atomicSendBuffer = new AtomicBuffer(sendBuffer);

    private final AtomicBuffer toClientBuffer = new AtomicBuffer(new byte[BROADCAST_BUFFER_SZ]);
    private final CopyBroadcastReceiver toClientReceiver =
        new CopyBroadcastReceiver(new BroadcastReceiver(toClientBuffer));
    private final BroadcastTransmitter toClientTransmitter = new BroadcastTransmitter(toClientBuffer);

    private final AtomicBuffer counterValuesBuffer = new AtomicBuffer(new byte[COUNTER_BUFFER_SZ]);

    private Signal signal;
    private MediaDriverProxy mediaDriverProxy;
    private ClientConductor conductor;
    private DataHandler dataHandler = mock(DataHandler.class);

    @Before
    public void setUp() throws Exception
    {

        mediaDriverProxy = mock(MediaDriverProxy.class);
        signal = mock(Signal.class);

        when(mediaDriverProxy.addPublication(any(), anyLong(), anyLong())).thenReturn(CORRELATION_ID);

        willNotifyNewBuffer();

        conductor = new ClientConductor(
            new MediaDriverBroadcastReceiver(toClientReceiver),
            mockBufferUsage,
            counterValuesBuffer,
            mediaDriverProxy,
            signal,
            AWAIT_TIMEOUT);

        newBufferMessage.wrap(atomicSendBuffer, 0);
        errorHeader.wrap(atomicSendBuffer, 0);
    }

    @After
    public void tearDown()
    {
        conductor.close();
    }

    // --------------------------------
    // Publication related interactions
    // --------------------------------

    @Test
    public void creatingChannelsShouldNotifyMediaDriver() throws Exception
    {
        addPublication();

        verify(mediaDriverProxy).addPublication(DESTINATION, CHANNEL_ID_1, SESSION_ID_1);
    }

    @Test(expected = MediaDriverTimeoutException.class)
    public void cannotCreatePublisherUntilBuffersMapped()
    {
        willSignalTimeOut();

        addPublication();
    }

    @Test(expected = RegistrationException.class)
    public void shouldFailToAddOnMediaDriverError()
    {
        doAnswer(
            (invocation) ->
            {
                conductor.onError(PUBLICATION_CHANNEL_ALREADY_EXISTS,
                                  "publication and session already exist on destination");
                return null;
            }).when(signal).await(anyLong());

        addPublication();
    }

    @Test
    public void conductorCachesPublicationInstances()
    {
        Publication firstPublication = addPublication();
        Publication secondPublication = addPublication();

        assertThat(firstPublication, sameInstance(secondPublication));
    }

    @Test
    public void releasingPublicationShouldNotifyMediaDriver() throws Exception
    {
        Publication publication = addPublication();
        willNotifyOperationSucceeded();

        publication.release();

        verifyBuffersReleased(SESSION_ID_1);
        verify(mediaDriverProxy).removePublication(DESTINATION, SESSION_ID_1, CHANNEL_ID_1);
    }

    @Test
    public void releasingPublicationShouldPurgeCache()
    {
        Publication firstPublication = addPublication();

        willNotifyOperationSucceeded();
        firstPublication.release();
        verifyBuffersReleased(SESSION_ID_1);

        willNotifyNewBuffer();
        Publication secondPublication = addPublication();

        assertThat(firstPublication, not(sameInstance(secondPublication)));
    }

    @Test(expected = RegistrationException.class)
    public void shouldFailToRemoveOnMediaDriverError()
    {

        Publication publication = addPublication();

        doAnswer(
            (invocation) ->
            {
                conductor.onError(INVALID_DESTINATION_IN_PUBLICATION, "destination unknown");
                return null;
            }).when(signal).await(anyLong());

        publication.release();
    }

    @Test
    public void publicationsOnlyClosedOnLastRelease() throws Exception
    {
        Publication publication = addPublication();
        addPublication();

        publication.release();
        verifyBuffersNotReleased(SESSION_ID_2);
        verify(mediaDriverProxy, never()).removePublication(DESTINATION, SESSION_ID_1, CHANNEL_ID_1);

        willNotifyOperationSucceeded();

        publication.release();
        verifyBuffersReleased(SESSION_ID_1);
        verify(mediaDriverProxy).removePublication(DESTINATION, SESSION_ID_1, CHANNEL_ID_1);
    }

    @Test
    public void closingAPublicationDoesNotRemoveOtherPublications() throws Exception
    {
        Publication publication = conductor.addPublication(DESTINATION, CHANNEL_ID_1, SESSION_ID_1);
        conductor.addPublication(DESTINATION, CHANNEL_ID_2, SESSION_ID_2);

        willNotifyOperationSucceeded();

        publication.release();

        verifyBuffersReleased(SESSION_ID_1);
        verify(mediaDriverProxy).removePublication(DESTINATION, SESSION_ID_1, CHANNEL_ID_1);

        verifyBuffersNotReleased(SESSION_ID_2);
        verify(mediaDriverProxy, never()).removePublication(DESTINATION, SESSION_ID_2, CHANNEL_ID_2);
    }

    // ---------------------------------
    // Subscription related interactions
    // ---------------------------------

    @Test
    public void registeringSubscriberNotifiesMediaDriver() throws Exception
    {
        addSubscription();

        verify(mediaDriverProxy).addSubscription(DESTINATION, CHANNEL_ID_1);
    }

    @Test
    public void removingSubscriberNotifiesMediaDriver()
    {
        final Subscription subscription = addSubscription();

        subscription.release();

        verify(mediaDriverProxy).removeSubscription(DESTINATION, CHANNEL_ID_1);
    }

    @Ignore("not implemented yet")
    @Test(expected = MediaDriverTimeoutException.class)
    public void cannotCreateSubscriberIfMediaDriverDoesNotReply()
    {
        willSignalTimeOut();

        addSubscription();
    }

    private Subscription addSubscription()
    {
        return conductor.addSubscription(DESTINATION, CHANNEL_ID_1, dataHandler);
    }

    private void sendNewBufferNotification(final int msgTypeId,
                                           final long sessionId,
                                           final long termId)
    {
        newBufferMessage.channelId(CHANNEL_ID_1)
                        .sessionId(sessionId)
                        .correlationId(CORRELATION_ID)
                        .termId(termId);

        IntStream.range(0, TermHelper.BUFFER_COUNT).forEach(
            (i) ->
            {
                newBufferMessage.location(i, sessionId + "-log-" + i);
                newBufferMessage.bufferOffset(i, 0);
                newBufferMessage.bufferLength(i, LOG_BUFFER_SZ);
            }
        );

        IntStream.range(0, TermHelper.BUFFER_COUNT).forEach(
            (i) ->
            {
                newBufferMessage.location(i + TermHelper.BUFFER_COUNT, sessionId + "-state-" + i);
                newBufferMessage.bufferOffset(i + TermHelper.BUFFER_COUNT, 0);
                newBufferMessage.bufferLength(i + TermHelper.BUFFER_COUNT, STATE_BUFFER_LENGTH);
            }
        );

        newBufferMessage.destination(DESTINATION);

        toClientTransmitter.transmit(msgTypeId, atomicSendBuffer, 0, newBufferMessage.length());
    }

    private void willSignalTimeOut()
    {
        doAnswer(
            (invocation) ->
            {
                Thread.sleep(AWAIT_TIMEOUT + 1);
                return null;
            }).when(signal).await(anyLong());
    }

    private void willNotifyOperationSucceeded()
    {
        doAnswer(
            (invocation) ->
            {
                conductor.operationSucceeded();
                return null;
            }).when(signal).await(anyLong());
    }

    private void willNotifyNewBuffer()
    {
        doAnswer(
            invocation ->
            {
                sendNewBufferNotification(ON_NEW_PUBLICATION, SESSION_ID_1, TERM_ID_1);
                conductor.doWork();
                return null;
            }).when(signal).await(anyLong());
    }

    private Publication addPublication()
    {
        return conductor.addPublication(DESTINATION, CHANNEL_ID_1, SESSION_ID_1);
    }
}
