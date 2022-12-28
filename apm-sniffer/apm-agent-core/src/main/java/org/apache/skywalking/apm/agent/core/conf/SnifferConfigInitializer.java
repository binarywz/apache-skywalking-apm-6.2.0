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

package org.apache.skywalking.apm.agent.core.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.AgentPackagePath;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.util.ConfigInitializer;
import org.apache.skywalking.apm.util.PropertyPlaceholderHelper;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * The <code>SnifferConfigInitializer</code> initializes all configs in several way.
 *
 * @author wusheng
 */
public class SnifferConfigInitializer {
    private static final ILog logger = LogManager.getLogger(SnifferConfigInitializer.class);
    private static String SPECIFIED_CONFIG_PATH = "skywalking_config";
    private static String DEFAULT_CONFIG_FILE_NAME = "/config/agent.config";
    private static String ENV_KEY_PREFIX = "skywalking.";
    private static boolean IS_INIT_COMPLETED = false;

    /**
     * If the specified agent config path is set, the agent will try to locate the specified agent config. If the
     * specified agent config path is not set , the agent will try to locate `agent.config`, which should be in the
     * /config directory of agent package.
     * <p>
     * Also try to override the config by system.properties. All the keys in this place should
     * start with {@link #ENV_KEY_PREFIX}. e.g. in env `skywalking.agent.service_name=yourAppName` to override
     * `agent.service_name` in config file.
     * <p>
     * At the end, `agent.service_name` and `collector.servers` must not be blank.
     *
     * 作用: 将最终的配置信息填充到Config的静态字段中，主要步骤如下
     * - 将agent.conf文件中全部配置信息填充到Config中相应的静态字段中
     * - 解析系统环境变量值，覆盖Config中相应的静态字段
     * - 解析Java Agent的参数，覆盖Config中相应的静态字段
     *
     * 在启动应用时，需要在VM Option中指定agent.config配置文件
     * -javaagent:D:\dev\binarywz\sky-walking\apache-skywalking-apm-6.2.0\skywalking-agent\skywalking-agent.jar
     * -Dskywalking_config=D:\dev\binarywz\sky-walking\skywalking-basic\demo-provider\src\main\resources\agent.config
     * agent.config 配置文件中的配置格式为"${配置项名称:默认值}"，具体配置项如下:
     * agent.service_name=${SW_AGENT_NAME:Your_ApplicationName}
     */
    public static void initialize(String agentOptions) throws ConfigNotFoundException, AgentPackageNotFoundException {
        InputStreamReader configFileStream;

        try {
            /**
             * 1.加载agent.config配置文件
             * - 优先根据环境变量(skywalking_config)指定的agent.config文件路径加载。
             *   若环境变量未指定skywalking_config配置，则到skywalking-agent.jar同级的config目录下查找agent.confg配置文件.
             * - 将agent.config文件中的配置信息加载到Properties对象之后，将使用PropertyPlaceholderHelper对配置信息进行解析，
             *   将当前的"${配置项名称:默认值}"格式的配置值，替换成其中的默认值
             * - 完成解析之后，会通过ConfigInitializer工具类，将配置信息填充到Config中的静态字段中
             * -
             */
            configFileStream = loadConfig();
            Properties properties = new Properties();
            properties.load(configFileStream);
            for (String key : properties.stringPropertyNames()) {
                String value = (String)properties.get(key);
                //replace the key's value. properties.replace(key,value) in jdk8+
                // 按照${配置项名称:默认值}的格式解析各个配置项
                properties.put(key, PropertyPlaceholderHelper.INSTANCE.replacePlaceholders(value, properties));
            }
            // 填充Config中的静态字段
            ConfigInitializer.initialize(properties, Config.class);
        } catch (Exception e) {
            logger.error(e, "Failed to read the config file, skywalking is going to run in default config.");
        }

        /**
         * 2.解析环境变量，并覆盖Config中相应的静态字段
         * - overrideConfigBySystemProp()方法中会遍历环境变量(即 System.getProperties()集合)，如果环境变是以"skywalking."开头的，
         *   则认为是SkyWalking的配置，同样会填充到Config类中，以覆盖agent.config中的默认值
         */
        try {
            overrideConfigBySystemProp();
        } catch (Exception e) {
            logger.error(e, "Failed to read the system properties.");
        }

        /**
         * 3.解析Java Agent参数，并覆盖Config中相应的静态字段
         * - overrideConfigByAgentOptions()方法解析的是Java Agent的参数，填充Config类的规则与前面两步相同，不再重复
         */
        if (!StringUtil.isEmpty(agentOptions)) {
            try {
                agentOptions = agentOptions.trim();
                logger.info("Agent options is {}.", agentOptions);

                overrideConfigByAgentOptions(agentOptions);
            } catch (Exception e) {
                logger.error(e, "Failed to parse the agent options, val is {}.", agentOptions);
            }
        }

        // 检测SERVICE_NAME和BACKEND_SERVICE两个配置项，若为空则抛异常
        if (StringUtil.isEmpty(Config.Agent.SERVICE_NAME)) {
            throw new ExceptionInInitializerError("`agent.service_name` is missing.");
        }
        if (StringUtil.isEmpty(Config.Collector.BACKEND_SERVICE)) {
            throw new ExceptionInInitializerError("`collector.backend_service` is missing.");
        }

        // 更新初始化标记
        IS_INIT_COMPLETED = true;
    }

    private static void overrideConfigByAgentOptions(String agentOptions) throws IllegalAccessException {
        Properties properties = new Properties();
        for (List<String> terms : parseAgentOptions(agentOptions)) {
            if (terms.size() != 2) {
                throw new IllegalArgumentException("[" + terms + "] is not a key-value pair.");
            }
            properties.put(terms.get(0), terms.get(1));
        }
        if (!properties.isEmpty()) {
            ConfigInitializer.initialize(properties, Config.class);
        }
    }

    private static List<List<String>> parseAgentOptions(String agentOptions) {
        List<List<String>> options = new ArrayList<List<String>>();
        List<String> terms = new ArrayList<String>();
        boolean isInQuotes = false;
        StringBuilder currentTerm = new StringBuilder();
        for (char c : agentOptions.toCharArray()) {
            if (c == '\'' || c == '"') {
                isInQuotes = !isInQuotes;
            } else if (c == '=' && !isInQuotes) {   // key-value pair uses '=' as separator
                terms.add(currentTerm.toString());
                currentTerm = new StringBuilder();
            } else if (c == ',' && !isInQuotes) {   // multiple options use ',' as separator
                terms.add(currentTerm.toString());
                currentTerm = new StringBuilder();

                options.add(terms);
                terms = new ArrayList<String>();
            } else {
                currentTerm.append(c);
            }
        }
        // add the last term and option without separator
        terms.add(currentTerm.toString());
        options.add(terms);
        return options;
    }

    public static boolean isInitCompleted() {
        return IS_INIT_COMPLETED;
    }

    /**
     * Override the config by system properties. The property key must start with `skywalking`, the result should be as same
     * as in `agent.config`
     * <p>
     * such as: Property key of `agent.service_name` should be `skywalking.agent.service_name`
     *
     */
    private static void overrideConfigBySystemProp() throws IllegalAccessException {
        Properties properties = new Properties();
        Properties systemProperties = System.getProperties();
        Iterator<Map.Entry<Object, Object>> entryIterator = systemProperties.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<Object, Object> prop = entryIterator.next();
            String key = prop.getKey().toString();
            if (key.startsWith(ENV_KEY_PREFIX)) {
                String realKey = key.substring(ENV_KEY_PREFIX.length());
                properties.put(realKey, prop.getValue());
            }
        }

        if (!properties.isEmpty()) {
            ConfigInitializer.initialize(properties, Config.class);
        }
    }

    /**
     * Load the specified config file or default config file
     *
     * @return the config file {@link InputStream}, or null if not needEnhance.
     */
    private static InputStreamReader loadConfig() throws AgentPackageNotFoundException, ConfigNotFoundException, ConfigReadFailedException {

        String specifiedConfigPath = System.getProperties().getProperty(SPECIFIED_CONFIG_PATH);
        File configFile = StringUtil.isEmpty(specifiedConfigPath) ? new File(AgentPackagePath.getPath(), DEFAULT_CONFIG_FILE_NAME) : new File(specifiedConfigPath);

        if (configFile.exists() && configFile.isFile()) {
            try {
                logger.info("Config file found in {}.", configFile);

                return new InputStreamReader(new FileInputStream(configFile), "UTF-8");
            } catch (FileNotFoundException e) {
                throw new ConfigNotFoundException("Failed to load agent.config", e);
            } catch (UnsupportedEncodingException e) {
                throw new ConfigReadFailedException("Failed to load agent.config", e);
            }
        }
        throw new ConfigNotFoundException("Failed to load agent.config.");
    }
}
