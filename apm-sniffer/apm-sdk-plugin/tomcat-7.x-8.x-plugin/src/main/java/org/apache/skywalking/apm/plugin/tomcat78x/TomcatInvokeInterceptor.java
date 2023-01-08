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


package org.apache.skywalking.apm.plugin.tomcat78x;

import java.lang.reflect.Method;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;

/**
 * {@link TomcatInvokeInterceptor} fetch the serialized context data by using {@link
 * HttpServletRequest#getHeader(String)}. The {@link TraceSegment#refs} of current trace segment will reference to the
 * trace segment id of the previous level if the serialized context is not null.
 */
public class TomcatInvokeInterceptor implements InstanceMethodsAroundInterceptor {

    /**
     * * The {@link TraceSegment#refs} of current trace segment will reference to the
     * trace segment id of the previous level if the serialized context is not null.
     *
     * @param objInst
     * @param method
     * @param allArguments
     * @param argumentsTypes
     * @param result change this result, if you want to truncate the method.
     * @throws Throwable
     */
    @Override public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes, MethodInterceptResult result) throws Throwable {
        // invoke()方法的第一个参数就是HttpServletRequest对象
        HttpServletRequest request = (HttpServletRequest)allArguments[0];
        // 创建一个空的ContextCarrier对象
        ContextCarrier contextCarrier = new ContextCarrier();

        /**
         * 从Http请求头中反序列化ContextCarrier
         */
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            next.setHeadValue(request.getHeader(next.getHeadKey()));
        }

        /**
         * 获取当前线程绑定的TracingContext，如果未绑定则会创建新TracingContext并绑定，同时还会创建EntrySpan，如果已存在EntrySpan，
         * 则再次调用其start()方法。这里的第一个请求参数是operationName(即EndpointName)，Tomcat场景下就是请求的URI
         */
        AbstractSpan span = ContextManager.createEntrySpan(request.getRequestURI(), contextCarrier);
        // 为EntrySpan添加Tags，记录请求的URL以及method的信息
        Tags.URL.set(span, request.getRequestURL().toString());
        Tags.HTTP.METHOD.set(span, request.getMethod());
        span.setComponent(ComponentsDefine.TOMCAT); // 设置component字段
        SpanLayer.asHttp(span); // 设置layer字段

    }

    @Override public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes, Object ret) throws Throwable {
        HttpServletResponse response = (HttpServletResponse)allArguments[1];

        AbstractSpan span = ContextManager.activeSpan();
        if (response.getStatus() >= 400) {
            span.errorOccurred();
            Tags.STATUS_CODE.set(span, Integer.toString(response.getStatus()));
        }
        ContextManager.stopSpan();
        ContextManager.getRuntimeContext().remove(Constants.FORWARD_REQUEST_FLAG);
        return ret;
    }

    @Override public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes, Throwable t) {
        AbstractSpan span = ContextManager.activeSpan();
        span.log(t);
        span.errorOccurred();
    }
}
