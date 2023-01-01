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
 *
 */

package org.apache.skywalking.apm.agent.core.remote;

import io.grpc.Channel;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.apache.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.apache.skywalking.apm.agent.core.dictionary.EndpointNameDictionary;
import org.apache.skywalking.apm.agent.core.dictionary.NetworkAddressDictionary;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.os.OSUtil;
import org.apache.skywalking.apm.network.common.KeyIntValuePair;
import org.apache.skywalking.apm.network.register.v2.RegisterGrpc;
import org.apache.skywalking.apm.network.register.v2.Service;
import org.apache.skywalking.apm.network.register.v2.ServiceInstance;
import org.apache.skywalking.apm.network.register.v2.ServiceInstancePingGrpc;
import org.apache.skywalking.apm.network.register.v2.ServiceInstancePingPkg;
import org.apache.skywalking.apm.network.register.v2.ServiceInstanceRegisterMapping;
import org.apache.skywalking.apm.network.register.v2.ServiceInstances;
import org.apache.skywalking.apm.network.register.v2.ServiceRegisterMapping;
import org.apache.skywalking.apm.network.register.v2.Services;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * @author wusheng
 *
 * Note: 服务注册，Register.proto
 * - rpc doServiceRegister (Services) returns (ServiceRegisterMapping): 将服务的名称注册到后端的OAP集群，
 *   参数Services中会携带当前服务的名称(其中还可以附加一些KV格式的信息);返回的ServiceRegisterMapping其实是多个KV，其中就包含了后端OAP服务端生成的ServiceId。
 * - doServiceInstanceRegister (ServiceInstances) returns (ServiceInstanceRegisterMapping): 将服务实例的名称注册到后端OAP集群
 *   参数ServiceInstances中会携带服务实例(ServiceInstance)的名称，以及serviceId、时间戳等信息;返回的ServiceInstanceRegisterMapping本质也是一堆KV，
 *   其中包含OAP为服务实例生成的ServiceInstanceId
 */
@DefaultImplementor
public class ServiceAndEndpointRegisterClient implements BootService, Runnable, GRPCChannelListener {
    private static final ILog logger = LogManager.getLogger(ServiceAndEndpointRegisterClient.class);
    private static String INSTANCE_UUID;

    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;
    /**
     * stub -> gRpc框架生成的客户端辅助类，可以帮助我们轻松完成请求的序列化、数据发送以及响应的反序列化
     */
    private volatile RegisterGrpc.RegisterBlockingStub registerBlockingStub;
    private volatile ServiceInstancePingGrpc.ServiceInstancePingBlockingStub serviceInstancePingStub;
    private volatile ScheduledFuture<?> applicationRegisterFuture;

    /**
     * 网络建立连接之后，回调statusChanged()方法更新registerBlockingStub字段
     * @param status
     */
    @Override
    public void statusChanged(GRPCChannelStatus status) {
        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            Channel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getChannel();
            // 网络连接建立完成后，会依赖该连接创建两个stub客户端
            registerBlockingStub = RegisterGrpc.newBlockingStub(channel);
            serviceInstancePingStub = ServiceInstancePingGrpc.newBlockingStub(channel);
        } else {
            // 网络连接断开时，更新两个stub字段(它们都是volatile修饰)
            registerBlockingStub = null;
            serviceInstancePingStub = null;
        }
        // 更新status字段，记录网络状态
        this.status = status;
    }

    @Override
    public void prepare() throws Throwable {
        // 注册到GRPCChannelManager来监听网络连接
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);

        // 生成当前ServiceInstance的唯一标识，优先使用gent.config文件中配置的INSTANCE_UUID，若未配置则随机生成
        INSTANCE_UUID = StringUtil.isEmpty(Config.Agent.INSTANCE_UUID) ? UUID.randomUUID().toString()
            .replaceAll("-", "") : Config.Agent.INSTANCE_UUID;
    }

    /**
     * ServiceAndEndpointRegisterClient也同时实现了Runnable接口，在boot()方法中会启动一个定时任务，默认每 3s 执行一次其run()方法，
     * 该定时任务首先会通过doServiceRegister()接口完成 Service 注册
     * @throws Throwable
     */
    @Override
    public void boot() throws Throwable {
        applicationRegisterFuture = Executors
            .newSingleThreadScheduledExecutor(new DefaultNamedThreadFactory("ServiceAndEndpointRegisterClient"))
            .scheduleAtFixedRate(new RunnableWithExceptionProtection(this, new RunnableWithExceptionProtection.CallbackWhenException() {
                @Override
                public void handle(Throwable t) {
                    logger.error("unexpected exception.", t);
                }
            }), 0, Config.Collector.APP_AND_SERVICE_REGISTER_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    @Override
    public void onComplete() throws Throwable {
    }

    @Override
    public void shutdown() throws Throwable {
        applicationRegisterFuture.cancel(true);
    }

    @Override
    public void run() {
        logger.debug("ServiceAndEndpointRegisterClient running, status:{}.", status);
        boolean shouldTry = true;
        while (GRPCChannelStatus.CONNECTED.equals(status) && shouldTry) {
            shouldTry = false;
            try {
                /**
                 * 检测当前Agent是否已经完成了Service注册
                 * - 若agent.conf配置文件中直接配置了serviceId，则无需进行服务注册，即服务注册的目标是获取serviceId
                 * - 根据GRPCChannel的连接状态，决定是否发送服务注册请求、服务实例注册请求以及心跳请求
                 */
                if (RemoteDownstreamConfig.Agent.SERVICE_ID == DictionaryUtil.nullValue()) {
                    // 再次检查网络状态
                    if (registerBlockingStub != null) {
                        // 通过doServiceRegister()接口进行Service注册
                        ServiceRegisterMapping serviceRegisterMapping = registerBlockingStub.doServiceRegister(
                            Services.newBuilder().addServices(Service.newBuilder().setServiceName(Config.Agent.SERVICE_NAME)).build());
                        if (serviceRegisterMapping != null) {
                            // 遍历所有KV
                            for (KeyIntValuePair registered : serviceRegisterMapping.getServicesList()) {
                                if (Config.Agent.SERVICE_NAME.equals(registered.getKey())) {
                                    // 记录serviceId
                                    RemoteDownstreamConfig.Agent.SERVICE_ID = registered.getValue();
                                    // 设置shouldTry，紧跟着会执行服务实例注册
                                    shouldTry = true;
                                }
                            }
                        }
                    }
                } else {
                    /**
                     * 完成服务注册之后，会进行服务实例注册
                     */
                    if (registerBlockingStub != null) {
                        if (RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID == DictionaryUtil.nullValue()) {
                            /**
                             * 调用doServiceInstanceRegister()接口，用serviceId和INSTANCE_UUID换取SERVICE_INSTANCE_ID
                             */
                            ServiceInstanceRegisterMapping instanceMapping = registerBlockingStub.doServiceInstanceRegister(ServiceInstances.newBuilder()
                                .addInstances(
                                    ServiceInstance.newBuilder()
                                        .setServiceId(RemoteDownstreamConfig.Agent.SERVICE_ID)
                                        // 除了serviceId，还会传递uuid、时间戳以及系统信息之类的
                                        .setInstanceUUID(INSTANCE_UUID)
                                        .setTime(System.currentTimeMillis())
                                        .addAllProperties(OSUtil.buildOSInfo())
                                ).build());
                            for (KeyIntValuePair serviceInstance : instanceMapping.getServiceInstancesList()) {
                                if (INSTANCE_UUID.equals(serviceInstance.getKey())) {
                                    int serviceInstanceId = serviceInstance.getValue();
                                    if (serviceInstanceId != DictionaryUtil.nullValue()) {
                                        // 记录serviceInstanceId
                                        RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID = serviceInstanceId;
                                    }
                                }
                            }
                        } else {
                            serviceInstancePingStub.doPing(ServiceInstancePingPkg.newBuilder()
                                .setServiceInstanceId(RemoteDownstreamConfig.Agent.SERVICE_INSTANCE_ID)
                                .setTime(System.currentTimeMillis())
                                .setServiceInstanceUUID(INSTANCE_UUID)
                                .build());

                            /**
                             * Endpoint/NetWorkAddress同步
                             * Trace数据中包含了请求的URL地址、RPC接口名称、HTTP服务或RPC服务的地址、数据库的IP以及端口等信息，
                             * 这些信息在整个服务上线稳定之后，不会经常发生变动。而在海量Trace中包含这些重复的字符串，会非常浪费网络带宽以及存储资源，
                             * 常见的解决方案是将字符串映射成数字编号并维护一张映射表，在传输、存储时使用映射后的数字编号，
                             * 在展示时根据映射表查询真正的字符串进行展示即可。这类似于编码、解码的思想。SkyWalking也是如此处理Trace中重复字符串的。
                             *
                             * SkyWalking中有两个DictionaryManager:
                             * - EndpointNameDictionary: 用于同步Endpoint字符串的映射关系
                             * - NetworkAddressDictionary: 用于同步网络地址的映射关系
                             * EndpointNameDictionary中维护了两个集合:
                             * - endpointDictionary: 记录已知的Endpoint名称映射的数字编号
                             * - unRegisterEndpoints: 记录了未知的 Endpoint名称
                             * EndpointNameDictionary对外提供了两类操作，一个是查询操作(核心实现在find0()方法)，在查询时首先去endpointDictionary集合
                             * 查找指定Endpoint名称是否已存在相应的数字编号，若存在则正常返回数字编号，若不存在则记录到unRegisterEndpoints集合中。
                             * 为了防止占用过多内存导致频繁GC，endpointDictionary和unRegisterEndpoints集合大小是有上限的(默认两者之和为 10^7);
                             *
                             * 另一个操作是同步操作(核心实现在syncRemoteDictionary()方法)，在ServiceAndEndpointRegisterClient收到心跳响应之后，
                             * 会将unRegisterEndpoints集合中未知的Endpoint名称发送到OAP集群，然后由OAP集群统一分配相应的数字编码
                             */
                            NetworkAddressDictionary.INSTANCE.syncRemoteDictionary(registerBlockingStub); // 同步Endpoint字符串的映射关系
                            EndpointNameDictionary.INSTANCE.syncRemoteDictionary(registerBlockingStub); // 用于同步网络地址的映射关系
                        }
                    }
                }
            } catch (Throwable t) {
                logger.error(t, "ServiceAndEndpointRegisterClient execute fail.");
                ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
            }
        }
    }
}
