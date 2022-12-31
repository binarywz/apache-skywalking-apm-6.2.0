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


package org.apache.skywalking.apm.plugin.jdbc.mysql.v8.define;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;

import java.util.Properties;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.agent.core.plugin.match.NameMatch.byName;

/**
 * interceptor the method {@link com.mysql.cj.jdbc.ConnectionImpl#getInstance(com.mysql.cj.conf.HostInfo)}
 * instead of {@link com.mysql.cj.jdbc.Driver#connect(String, Properties)}
 * @author: dingshaocheng
 *
 * Note: ConnectionImplCreateInstrumentation这个插件拦截的是com.mysql.jdbc.ConnectionImpl.getInstance()这个静态方法
 */
public class ConnectionImplCreateInstrumentation extends AbstractMysqlInstrumentation {

    private static final String JDBC_ENHANCE_CLASS = "com.mysql.cj.jdbc.ConnectionImpl";

    private static final String CONNECT_METHOD = "getInstance";


    @Override
    protected StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        /**
         * StaticMethodsInterceptPoint描述了当前插件要拦截目标类的哪些static静态方法，以及委托给哪个类去增强
         * Skywalking中StaticMethodsInterceptPoint接口的实现基本都是这种匿名内部类
         */
        return new StaticMethodsInterceptPoint[] {
            new StaticMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    // 增强getInstance方法
                    return named(CONNECT_METHOD);
                }

                @Override
                public String getMethodsInterceptor() {
                    // 委托给ConnectionCreateInterceptor进行增强
                    return "org.apache.skywalking.apm.plugin.jdbc.mysql.v8.ConnectionCreateInterceptor";
                }

                @Override
                public boolean isOverrideArgs() {
                    // 增强过程中无需修改方法参数
                    return false;
                }
            }
        };
    }

    @Override
    protected ClassMatch enhanceClass() {
        // 拦截目标类为'com.mysql.cj.jdbc.ConnectionImpl'
        return byName(JDBC_ENHANCE_CLASS);
    }
}
