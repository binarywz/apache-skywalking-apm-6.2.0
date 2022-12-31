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
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.apache.skywalking.apm.agent.core.plugin.match.NameMatch.byName;

/**
 * Note:  mysql-8.x-plugin模块的skywalking-plugin.def文件定义了ConnectionInstrumentation插件类，
 * 它会被AgentClassLoader加载，其enhanceClass()方法返回的Matcher拦截的目标类是'com.mysql.cj.jdbc.ConnectionImpl'
 *
 */
public class ConnectionInstrumentation extends AbstractMysqlInstrumentation {

    /**
     * 返回空数组表示不会拦截构造方法，但是依然会修改ConnectionImpl，为其添加_$EnhancedClassField_ws字段并实现EnhanceInstance接口
     * @return
     */
    @Override protected ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return new ConstructorInterceptPoint[0];
    }

    @Override protected InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        return new InstanceMethodsInterceptPoint[] {
            /**
             * 负责拦截ConnectionImpl的prepareStatement()方法，并委托给CreatePreparedStatementInterceptor
             */
            new InstanceMethodsInterceptPoint() {
                @Override public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.PREPARE_STATEMENT_METHOD_NAME);
                }

                /**
                 * CreatePreparedStatementInterceptor中，只实现了after()方法，before()和handleMethodException()方法都是空实现
                 * @return
                 */
                @Override public String getMethodsInterceptor() {
                    return org.apache.skywalking.apm.plugin.jdbc.mysql.Constants.CREATE_PREPARED_STATEMENT_INTERCEPTOR;
                }

                @Override public boolean isOverrideArgs() {
                    return false;
                }
            },
            new InstanceMethodsInterceptPoint() {
                @Override public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.PREPARE_CALL_METHOD_NAME);
                }

                @Override public String getMethodsInterceptor() {
                    return org.apache.skywalking.apm.plugin.jdbc.mysql.Constants.CREATE_CALLABLE_STATEMENT_INTERCEPTOR;
                }

                @Override public boolean isOverrideArgs() {
                    return false;
                }
            },
            new InstanceMethodsInterceptPoint() {
                @Override public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.CREATE_STATEMENT_METHOD_NAME).and(takesArguments(2));
                }

                @Override public String getMethodsInterceptor() {
                    return org.apache.skywalking.apm.plugin.jdbc.mysql.Constants.CREATE_STATEMENT_INTERCEPTOR;
                }

                @Override public boolean isOverrideArgs() {
                    return false;
                }
            },
            new InstanceMethodsInterceptPoint() {
                @Override public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.COMMIT_METHOD_NAME).or(named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.ROLLBACK_METHOD_NAME)).or(named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.CLOSE_METHOD_NAME)).or(named(org.apache.skywalking.apm.plugin.jdbc.define.Constants.RELEASE_SAVE_POINT_METHOD_NAME));
                }

                @Override public String getMethodsInterceptor() {
                    return org.apache.skywalking.apm.plugin.jdbc.define.Constants.SERVICE_METHOD_INTERCEPT_CLASS;
                }

                @Override public boolean isOverrideArgs() {
                    return false;
                }
            },
            new InstanceMethodsInterceptPoint() {
                @Override public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named("setCatalog");
                }

                @Override public String getMethodsInterceptor() {
                    return org.apache.skywalking.apm.plugin.jdbc.mysql.Constants.SET_CATALOG_INTERCEPTOR;
                }

                @Override public boolean isOverrideArgs() {
                    return false;
                }
            }
        };

    }


    /**
     * 匹配当前插件要增强的目标类，此处为com.mysql.cj.jdbc.ConnectionImpl
     * @return
     */
    @Override protected ClassMatch enhanceClass() {
        return byName("com.mysql.cj.jdbc.ConnectionImpl");
    }

}
