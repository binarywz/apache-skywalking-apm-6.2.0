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


package org.apache.skywalking.apm.agent.core.plugin;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * Basic abstract class of all sky-walking auto-instrumentation plugins.
 * <p>
 * It provides the outline of enhancing the target class.
 * If you want to know more about enhancing, you should go to see {@link ClassEnhancePluginDefine}
 *
 * Note: 所有Agent插件类的顶级父类，以下为核心方法(按执行顺序排序)
 * - enhanceClass(): 用于匹配当前插件要增强的目标类
 * - define(): 插件类增强逻辑的入口，底层会调用下面的enhance()/witnessClass()
 * - witnessClass(): 一个开源组件可能会有多个版本，插件会通过该方法识别组件的不同版本，防止对不兼容的版本进行增强
 * - enhance(): 真正执行增强逻辑的地方
 */
public abstract class AbstractClassEnhancePluginDefine {
    private static final ILog logger = LogManager.getLogger(AbstractClassEnhancePluginDefine.class);

    /**
     * Main entrance of enhancing the class.
     *
     * @param typeDescription target class description.
     * @param builder byte-buddy's builder to manipulate target class's bytecode.
     * @param classLoader load the given transformClass
     * @return the new builder, or <code>null</code> if not be enhanced.
     * @throws PluginException when set builder failure.
     */
    public DynamicType.Builder<?> define(TypeDescription typeDescription,
                                         DynamicType.Builder<?> builder, ClassLoader classLoader, EnhanceContext context) throws PluginException {
        String interceptorDefineClassName = this.getClass().getName();
        String transformClassName = typeDescription.getTypeName();
        if (StringUtil.isEmpty(transformClassName)) {
            logger.warn("classname of being intercepted is not defined by {}.", interceptorDefineClassName);
            return null;
        }

        logger.debug("prepare to enhance class {} by {}.", transformClassName, interceptorDefineClassName);

        /**
         * find witness classes for enhance class
         */
        String[] witnessClasses = witnessClasses();
        if (witnessClasses != null) {
            for (String witnessClass : witnessClasses) {
                // 判断指定类加载器中是否存在witnessClass()指定的类
                if (!WitnessClassFinder.INSTANCE.exist(witnessClass, classLoader)) {
                    logger.warn("enhance class {} by plugin {} is not working. Because witness class {} is not existed.", transformClassName, interceptorDefineClassName,
                        witnessClass);
                    return null;
                }
            }
        }

        /**
         * find origin class source code for interceptor
         */
        DynamicType.Builder<?> newClassBuilder = this.enhance(typeDescription, builder, classLoader, context);

        context.initializationStageCompleted();
        logger.debug("enhance class {} by {} completely.", transformClassName, interceptorDefineClassName);

        return newClassBuilder;
    }

    protected abstract DynamicType.Builder<?> enhance(TypeDescription typeDescription,
        DynamicType.Builder<?> newClassBuilder, ClassLoader classLoader, EnhanceContext context) throws PluginException;

    /**
     * Define the {@link ClassMatch} for filtering class.
     *
     * @return {@link ClassMatch}
     */
    protected abstract ClassMatch enhanceClass();

    /**
     * Witness classname list. Why need witness classname? Let's see like this: A library existed two released versions
     * (like 1.0, 2.0), which include the same target classes, but because of version iterator, they may have the same
     * name, but different methods, or different method arguments list. So, if I want to target the particular version
     * (let's say 1.0 for example), version number is obvious not an option, this is the moment you need "Witness
     * classes". You can add any classes only in this particular release version ( something like class
     * com.company.1.x.A, only in 1.0 ), and you can achieve the goal.
     *
     * @return
     *
     * Note: 开源组件和工具类库的功能会不断增加，不同版本中同名类的功能和结构已经发生了翻天覆地的变化。要通过一个SkyWalking Agent插件完成对一个开源组件所有版本的增强，
     * 是非常难实现的，即使勉强能够实现，该插件的实现也会变的非常臃肿，扩展性也会成问题
     * Skywalking怎么解决这个问题呢？以MySQL为例，Skywalking为每个版本的mysql-connector-java.jar提供了不同版本的插件，这些插件的witnessClass()方法返回值不同，
     * 具体返回的是对应版本的mysql-connector-java.jar所特有的一个类:
     * - mysql-5.x-plugin -> com.mysql.jdbc.ConnectionImpl
     * - mysql-8.x-plugin -> com.mysql.cj.interceptors.QueryInterceptor
     * 若当前类加载器无法扫描到插件witnessClass()方法指定的类，表示当前插件版本不合适，即使拦截到了目标类，也不能增进行增强
     */
    protected String[] witnessClasses() {
        return new String[] {};
    }
}
