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

package org.apache.skywalking.apm.agent.core.boot;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader;

/**
 * The <code>ServiceManager</code> bases on {@link ServiceLoader},
 * load all {@link BootService} implementations.
 *
 * @author wusheng
 */
public enum ServiceManager {
    INSTANCE;

    private static final ILog logger = LogManager.getLogger(ServiceManager.class);
    private Map<Class, BootService> bootedServices = Collections.emptyMap();

    public void boot() {
        bootedServices = loadAllServices();

        /**
         * 在前一步找到要使用的BootService实现后，ServiceManager将统一初始化bootServices集合中的BootService实现，
         * 同样是在ServiceManager.boot()方法中，会逐个调用BootService实现的prepare()/startup()/onComplete()方法
         */
        prepare();
        startup();
        onComplete();
    }

    public void shutdown() {
        for (BootService service : bootedServices.values()) {
            try {
                service.shutdown();
            } catch (Throwable e) {
                logger.error(e, "ServiceManager try to shutdown [{}] fail.", service.getClass().getName());
            }
        }
    }

    /**
     * 实例化全部BootService接口
     * @return
     */
    private Map<Class, BootService> loadAllServices() {
        Map<Class, BootService> bootedServices = new LinkedHashMap<Class, BootService>();
        List<BootService> allServices = new LinkedList<BootService>();
        /**
         * 通过Java SPI加载并实例化META-INF/services下的全部BootService接口实现
         *
         * org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient
         * org.apache.skywalking.apm.agent.core.context.ContextManager
         * org.apache.skywalking.apm.agent.core.sampling.SamplingService
         * org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager
         * org.apache.skywalking.apm.agent.core.jvm.JVMService
         * org.apache.skywalking.apm.agent.core.remote.ServiceAndEndpointRegisterClient
         * org.apache.skywalking.apm.agent.core.context.ContextManagerExtendService
         */
        load(allServices);
        /**
         * 遍历所有BootService实现，针对BootService上的@DefaultImplementor和@OverrideImplementor注解进行处理:
         * - @DefaultImplementor注解用于标识BootService接口的默认实现
         * - @OverrideImplementor注解用于覆盖默认BootService实现，通过其value字段指定要覆盖的默认实现
         */
        Iterator<BootService> serviceIterator = allServices.iterator();
        while (serviceIterator.hasNext()) {
            BootService bootService = serviceIterator.next();

            Class<? extends BootService> bootServiceClass = bootService.getClass();
            boolean isDefaultImplementor = bootServiceClass.isAnnotationPresent(DefaultImplementor.class);
            if (isDefaultImplementor) {
                if (!bootedServices.containsKey(bootServiceClass)) {
                    bootedServices.put(bootServiceClass, bootService);
                } else {
                    //ignore the default service
                }
            } else {
                OverrideImplementor overrideImplementor = bootServiceClass.getAnnotation(OverrideImplementor.class);
                if (overrideImplementor == null) {
                    if (!bootedServices.containsKey(bootServiceClass)) {
                        bootedServices.put(bootServiceClass, bootService);
                    } else {
                        throw new ServiceConflictException("Duplicate service define for :" + bootServiceClass);
                    }
                } else {
                    Class<? extends BootService> targetService = overrideImplementor.value();
                    if (bootedServices.containsKey(targetService)) {
                        boolean presentDefault = bootedServices.get(targetService).getClass().isAnnotationPresent(DefaultImplementor.class);
                        if (presentDefault) {
                            bootedServices.put(targetService, bootService);
                        } else {
                            throw new ServiceConflictException("Service " + bootServiceClass + " overrides conflict, " +
                                "exist more than one service want to override :" + targetService);
                        }
                    } else {
                        bootedServices.put(targetService, bootService);
                    }
                }
            }

        }
        return bootedServices;
    }

    private void prepare() {
        for (BootService service : bootedServices.values()) {
            try {
                service.prepare();
            } catch (Throwable e) {
                logger.error(e, "ServiceManager try to pre-start [{}] fail.", service.getClass().getName());
            }
        }
    }

    private void startup() {
        for (BootService service : bootedServices.values()) {
            try {
                service.boot();
            } catch (Throwable e) {
                logger.error(e, "ServiceManager try to start [{}] fail.", service.getClass().getName());
            }
        }
    }

    private void onComplete() {
        for (BootService service : bootedServices.values()) {
            try {
                service.onComplete();
            } catch (Throwable e) {
                logger.error(e, "Service [{}] AfterBoot process fails.", service.getClass().getName());
            }
        }
    }

    /**
     * Find a {@link BootService} implementation, which is already started.
     *
     * @param serviceClass class name.
     * @param <T> {@link BootService} implementation class.
     * @return {@link BootService} instance
     */
    public <T extends BootService> T findService(Class<T> serviceClass) {
        return (T)bootedServices.get(serviceClass);
    }

    void load(List<BootService> allServices) {
        Iterator<BootService> iterator = ServiceLoader.load(BootService.class, AgentClassLoader.getDefault()).iterator();
        while (iterator.hasNext()) {
            allServices.add(iterator.next());
        }
    }
}
