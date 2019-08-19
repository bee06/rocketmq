/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.client.consumer;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.consumer.store.OffsetStore;
import org.apache.rocketmq.client.consumer.store.ReadOffsetType;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.CommunicationMode;
import org.apache.rocketmq.client.impl.FindBrokerResult;
import org.apache.rocketmq.client.impl.MQAdminImpl;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.client.impl.MQClientManager;
import org.apache.rocketmq.client.impl.consumer.AssignedMessageQueue;
import org.apache.rocketmq.client.impl.consumer.DefaultLitePullConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.PullAPIWrapper;
import org.apache.rocketmq.client.impl.consumer.PullResultExt;
import org.apache.rocketmq.client.impl.consumer.RebalanceImpl;
import org.apache.rocketmq.client.impl.consumer.RebalanceService;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.common.message.MessageClientExt;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.header.PullMessageRequestHeader;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.failBecauseExceptionWasNotThrown;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DefaultLitePullConsumerTest {
    @Spy
    private MQClientInstance mQClientFactory = MQClientManager.getInstance().getAndCreateMQClientInstance(new ClientConfig());

    @Mock
    private MQClientAPIImpl mQClientAPIImpl;
    @Mock
    private MQAdminImpl mQAdminImpl;

    private RebalanceImpl rebalanceImpl;
    private OffsetStore offsetStore;
    private DefaultLitePullConsumerImpl litePullConsumerImpl;
    private String consumerGroup = "LitePullConsumerGroup";
    private String topic = "LitePullConsumerTest";
    private String brokerName = "BrokerA";

    @Before
    public void init() throws Exception {
        Field field = MQClientInstance.class.getDeclaredField("rebalanceService");
        field.setAccessible(true);
        RebalanceService rebalanceService = (RebalanceService) field.get(mQClientFactory);
        field = RebalanceService.class.getDeclaredField("waitInterval");
        field.setAccessible(true);
        field.set(rebalanceService, 100);
    }

    @Test
    public void testAssign_PollMessageSuccess() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createStartLitePullConsumer();
        try {
            MessageQueue messageQueue = createMessageQueue();
            litePullConsumer.assign(Collections.singletonList(messageQueue));
            List<MessageExt> result = litePullConsumer.poll();
            assertThat(result.get(0).getTopic()).isEqualTo(topic);
            assertThat(result.get(0).getBody()).isEqualTo(new byte[] {'a'});
        } finally {
            litePullConsumer.shutdown();
        }
    }

    @Test
    public void testSubscribe_PollMessageSuccess() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createSubscribeLitePullConsumer();
        try {
            Set<MessageQueue> messageQueueSet = new HashSet<MessageQueue>();
            messageQueueSet.add(createMessageQueue());
            litePullConsumerImpl.updateTopicSubscribeInfo(topic, messageQueueSet);
            litePullConsumer.setPollTimeoutMillis(20 * 1000);
            List<MessageExt> result = litePullConsumer.poll();
            assertThat(result.get(0).getTopic()).isEqualTo(topic);
            assertThat(result.get(0).getBody()).isEqualTo(new byte[] {'a'});
        } finally {
            litePullConsumer.shutdown();
        }
    }

    @Test
    public void testSubscribe_BroadcastPollMessageSuccess() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createBroadcastLitePullConsumer();
        try {
            Set<MessageQueue> messageQueueSet = new HashSet<MessageQueue>();
            messageQueueSet.add(createMessageQueue());
            litePullConsumerImpl.updateTopicSubscribeInfo(topic, messageQueueSet);
            litePullConsumer.setPollTimeoutMillis(20 * 1000);
            List<MessageExt> result = litePullConsumer.poll();
            assertThat(result.get(0).getTopic()).isEqualTo(topic);
            assertThat(result.get(0).getBody()).isEqualTo(new byte[] {'a'});
        } finally {
            litePullConsumer.shutdown();
        }
    }

    @Test
    public void testSubscriptionType_AssignAndSubscribeExclusive() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createStartLitePullConsumer();
        try {
            litePullConsumer.subscribe(topic, "*");
            litePullConsumer.assign(Collections.singletonList(createMessageQueue()));
            failBecauseExceptionWasNotThrown(IllegalStateException.class);
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining("Subscribe and assign are mutually exclusive.");
        } finally {
            litePullConsumer.shutdown();
        }
    }

    @Test
    public void testFetchMesseageQueues_FetchMessageQueuesBeforeStart() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createNotStartLitePullConsumer();
        try {
            litePullConsumer.fetchMessageQueues(topic);
            failBecauseExceptionWasNotThrown(IllegalStateException.class);
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining("The consumer not running, please start it first.");
        } finally {
            litePullConsumer.shutdown();
        }
    }

    @Test
    public void testSeek_SeekOffsetIllegal() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createStartLitePullConsumer();
        when(mQAdminImpl.minOffset(any(MessageQueue.class))).thenReturn(0L);
        when(mQAdminImpl.maxOffset(any(MessageQueue.class))).thenReturn(100L);
        MessageQueue messageQueue = createMessageQueue();
        litePullConsumer.assign(Collections.singletonList(messageQueue));
        try {
            litePullConsumer.seek(messageQueue, -1);
            failBecauseExceptionWasNotThrown(MQClientException.class);
        } catch (MQClientException e) {
            assertThat(e).hasMessageContaining("min offset = 0");
        }

        try {
            litePullConsumer.seek(messageQueue, 1000);
            failBecauseExceptionWasNotThrown(MQClientException.class);
        } catch (MQClientException e) {
            assertThat(e).hasMessageContaining("max offset = 100");
        }
        litePullConsumer.shutdown();
    }

    @Test
    public void testSeek_SeekOffsetSuccess() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createStartLitePullConsumer();
        when(mQAdminImpl.minOffset(any(MessageQueue.class))).thenReturn(0L);
        when(mQAdminImpl.maxOffset(any(MessageQueue.class))).thenReturn(100L);
        MessageQueue messageQueue = createMessageQueue();
        litePullConsumer.assign(Collections.singletonList(messageQueue));
        litePullConsumer.seek(messageQueue, 50);
        Field field = DefaultLitePullConsumerImpl.class.getDeclaredField("assignedMessageQueue");
        field.setAccessible(true);
        AssignedMessageQueue assignedMessageQueue = (AssignedMessageQueue) field.get(litePullConsumerImpl);
        assertEquals(assignedMessageQueue.getSeekOffset(messageQueue), 50);
        assertEquals(assignedMessageQueue.getConusmerOffset(messageQueue), 50);
        litePullConsumer.shutdown();
    }

    @Test
    public void testSeek_MessageQueueNotInAssignList() throws Exception {
        DefaultLitePullConsumer litePullConsumer = createStartLitePullConsumer();
        try {
            litePullConsumer.seek(createMessageQueue(), 0);
            failBecauseExceptionWasNotThrown(MQClientException.class);
        } catch (MQClientException e) {
            assertThat(e).hasMessageContaining("The message queue is not in assigned list");
        } finally {
            litePullConsumer.shutdown();
        }
    }

    private MessageQueue createMessageQueue() {
        MessageQueue messageQueue = new MessageQueue();
        messageQueue.setBrokerName(brokerName);
        messageQueue.setQueueId(0);
        messageQueue.setTopic(topic);
        return messageQueue;
    }

    private void initDefaultLitePullConsumer(DefaultLitePullConsumer litePullConsumer) throws Exception {

        Field field = DefaultLitePullConsumer.class.getDeclaredField("defaultLitePullConsumerImpl");
        field.setAccessible(true);
        litePullConsumerImpl = (DefaultLitePullConsumerImpl) field.get(litePullConsumer);
        field = DefaultLitePullConsumerImpl.class.getDeclaredField("mQClientFactory");
        field.setAccessible(true);
        field.set(litePullConsumerImpl, mQClientFactory);

        PullAPIWrapper pullAPIWrapper = litePullConsumerImpl.getPullAPIWrapper();
        field = PullAPIWrapper.class.getDeclaredField("mQClientFactory");
        field.setAccessible(true);
        field.set(pullAPIWrapper, mQClientFactory);

        field = MQClientInstance.class.getDeclaredField("mQClientAPIImpl");
        field.setAccessible(true);
        field.set(mQClientFactory, mQClientAPIImpl);

        field = MQClientInstance.class.getDeclaredField("mQAdminImpl");
        field.setAccessible(true);
        field.set(mQClientFactory, mQAdminImpl);

        field = DefaultLitePullConsumerImpl.class.getDeclaredField("rebalanceImpl");
        field.setAccessible(true);
        rebalanceImpl = (RebalanceImpl) field.get(litePullConsumerImpl);
        field = RebalanceImpl.class.getDeclaredField("mQClientFactory");
        field.setAccessible(true);
        field.set(rebalanceImpl, mQClientFactory);

        offsetStore = spy(litePullConsumerImpl.getOffsetStore());
        field = DefaultLitePullConsumerImpl.class.getDeclaredField("offsetStore");
        field.setAccessible(true);
        field.set(litePullConsumerImpl, offsetStore);

        when(mQClientFactory.getMQClientAPIImpl().pullMessage(anyString(), any(PullMessageRequestHeader.class),
            anyLong(), any(CommunicationMode.class), nullable(PullCallback.class)))
            .thenAnswer(new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock mock) throws Throwable {
                    PullMessageRequestHeader requestHeader = mock.getArgument(1);
                    MessageClientExt messageClientExt = new MessageClientExt();
                    messageClientExt.setTopic(topic);
                    messageClientExt.setQueueId(0);
                    messageClientExt.setMsgId("123");
                    messageClientExt.setBody(new byte[] {'a'});
                    messageClientExt.setOffsetMsgId("234");
                    messageClientExt.setBornHost(new InetSocketAddress(8080));
                    messageClientExt.setStoreHost(new InetSocketAddress(8080));
                    PullResult pullResult = createPullResult(requestHeader, PullStatus.FOUND, Collections.<MessageExt>singletonList(messageClientExt));
                    return pullResult;
                }
            });

        when(mQClientFactory.findBrokerAddressInSubscribe(anyString(), anyLong(), anyBoolean())).thenReturn(new FindBrokerResult("127.0.0.1:10911", false));

        doReturn(Collections.singletonList(mQClientFactory.getClientId())).when(mQClientFactory).findConsumerIdList(anyString(), anyString());

        doReturn(123L).when(offsetStore).readOffset(any(MessageQueue.class), any(ReadOffsetType.class));
    }

    private DefaultLitePullConsumer createSubscribeLitePullConsumer() throws Exception {
        DefaultLitePullConsumer litePullConsumer = new DefaultLitePullConsumer(consumerGroup + System.currentTimeMillis());
        litePullConsumer.setNamesrvAddr("127.0.0.1:9876");
        litePullConsumer.subscribe(topic, "*");

        litePullConsumer.start();
        initDefaultLitePullConsumer(litePullConsumer);
        return litePullConsumer;
    }

    private DefaultLitePullConsumer createBroadcastLitePullConsumer() throws Exception {
        DefaultLitePullConsumer litePullConsumer = new DefaultLitePullConsumer(consumerGroup + System.currentTimeMillis());
        litePullConsumer.setNamesrvAddr("127.0.0.1:9876");
        litePullConsumer.setMessageModel(MessageModel.BROADCASTING);
        litePullConsumer.subscribe(topic, "*");
        litePullConsumer.start();
        initDefaultLitePullConsumer(litePullConsumer);
        return litePullConsumer;
    }

    private DefaultLitePullConsumer createStartLitePullConsumer() throws Exception {
        DefaultLitePullConsumer litePullConsumer = new DefaultLitePullConsumer(consumerGroup + System.currentTimeMillis());
        litePullConsumer.setNamesrvAddr("127.0.0.1:9876");
        litePullConsumer.start();
        initDefaultLitePullConsumer(litePullConsumer);
        return litePullConsumer;
    }

    private DefaultLitePullConsumer createNotStartLitePullConsumer() {
        DefaultLitePullConsumer litePullConsumer = new DefaultLitePullConsumer(consumerGroup + System.currentTimeMillis());
        return litePullConsumer;
    }

    private PullResultExt createPullResult(PullMessageRequestHeader requestHeader, PullStatus pullStatus,
        List<MessageExt> messageExtList) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (MessageExt messageExt : messageExtList) {
            outputStream.write(MessageDecoder.encode(messageExt, false));
        }
        return new PullResultExt(pullStatus, requestHeader.getQueueOffset() + messageExtList.size(), 123, 2048, messageExtList, 0, outputStream.toByteArray());
    }

}