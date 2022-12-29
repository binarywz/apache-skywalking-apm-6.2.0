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

package org.apache.skywalking.apm.agent.core.plugin.loader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.AgentPackagePath;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.PluginBootstrap;

/**
 * The <code>AgentClassLoader</code> represents a classloader,
 * which is in charge of finding plugins and interceptors.
 *
 * @author wusheng
 *
 * Note: 目的是不在应用的classpath中引入Skywlaking的插件jar包，这样就可以让应用无依赖、无感知的加载插件
 * 主要作用是从其classpath下加载类(或资源文件):
 * - findClass()
 * - findResource()
 */
public class AgentClassLoader extends ClassLoader {

    static {
        tryRegisterAsParallelCapable();
    }

    private static final ILog logger = LogManager.getLogger(AgentClassLoader.class);
    /**
     * The default class loader for the agent.
     */
    private static AgentClassLoader DEFAULT_LOADER;

    /**
     * classpath字段在构造函数中初始化，该字段指向了AgentClassLoader要扫描的目录:
     * skywalking-agent.jar包同级别的plugins目录和activations目录
     */
    private List<File> classpath;
    /**
     * classpath下所有的jar文件，getAllJars()
     */
    private List<Jar> allJars;
    private ReentrantLock jarScanLock = new ReentrantLock();

    /**
     * Functional Description: solve the classloader dead lock when jvm start
     * only support JDK7+, since ParallelCapable appears in JDK7+
     *
     * 开启类加载器的并行加载模式 -> 降低锁的粒度，从ClassLoader级别缩小到具体加载某一个Class，提高并发度
     * registerAsParallelCapable() -> Reflection.getCallerClass().asSubclass(ClassLoader.class);
     * - 拿到调用者类的ClassLoader，并将当前调用者类转换成ClassLoader的一个子类 -> 保证调用者类是ClassLoader的子类，若调用者类非ClassLoader子类转换类型时会报错
     * ParallelLoaders.register(callerClass) -> Set<Class<? extends ClassLoader>> loaderTypes
     * - loaderTypes -> 并行能力类加载器
     * 若当前类加载器注册成为并行之后，parallelLockMap不为空，具体见{@link ClassLoader#ClassLoader()}
     * loadClass的时候会获取锁，具体见{@link java.lang.ClassLoader#getClassLoadingLock(java.lang.String)}
     * protected Object getClassLoadingLock(String className) {
     *     Object lock = this;
     *     if (parallelLockMap != null) {
     *         Object newLock = new Object();
     *         lock = parallelLockMap.putIfAbsent(className, newLock);
     *         if (lock == null) {
     *             lock = newLock;
     *         }
     *     }
     *     return lock;
     * }
     */
    private static void tryRegisterAsParallelCapable() {
        Method[] methods = ClassLoader.class.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            String methodName = method.getName();
            if ("registerAsParallelCapable".equalsIgnoreCase(methodName)) {
                try {
                    method.setAccessible(true);
                    method.invoke(null);
                } catch (Exception e) {
                    logger.warn(e, "can not invoke ClassLoader.registerAsParallelCapable()");
                }
                return;
            }
        }
    }

    public static AgentClassLoader getDefault() {
        return DEFAULT_LOADER;
    }

    /**
     * Init the default
     *
     * @throws AgentPackageNotFoundException
     */
    public static void initDefaultLoader() throws AgentPackageNotFoundException {
        if (DEFAULT_LOADER == null) {
            synchronized (AgentClassLoader.class) {
                if (DEFAULT_LOADER == null) {
                    DEFAULT_LOADER = new AgentClassLoader(PluginBootstrap.class.getClassLoader());
                }
            }
        }
    }

    public AgentClassLoader(ClassLoader parent) throws AgentPackageNotFoundException {
        super(parent); // 双亲委派机制
        /**
         * 获取skywalking.jar所在的目录
         */
        File agentDictionary = AgentPackagePath.getPath();
        classpath = new LinkedList<File>();
        /**
         * 初始化classpath集合，指向skywalking-agent.jar包同目录的两个目录
         */
        classpath.add(new File(agentDictionary, "plugins"));
        classpath.add(new File(agentDictionary, "activations"));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        List<Jar> allJars = getAllJars();
        String path = name.replace('.', '/').concat(".class");
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(path);
            if (entry != null) {
                try {
                    URL classFileUrl = new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + path);
                    byte[] data = null;
                    BufferedInputStream is = null;
                    ByteArrayOutputStream baos = null;
                    try {
                        is = new BufferedInputStream(classFileUrl.openStream());
                        baos = new ByteArrayOutputStream();
                        int ch = 0;
                        while ((ch = is.read()) != -1) {
                            baos.write(ch);
                        }
                        data = baos.toByteArray();
                    } finally {
                        if (is != null)
                            try {
                                is.close();
                            } catch (IOException ignored) {
                            }
                        if (baos != null)
                            try {
                                baos.close();
                            } catch (IOException ignored) {
                            }
                    }
                    return defineClass(name, data, 0, data.length);
                } catch (MalformedURLException e) {
                    logger.error(e, "find class fail.");
                } catch (IOException e) {
                    logger.error(e, "find class fail.");
                }
            }
        }
        throw new ClassNotFoundException("Can't find " + name);
    }

    @Override
    protected URL findResource(String name) {
        List<Jar> allJars = getAllJars();
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(name);
            if (entry != null) {
                try {
                    return new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + name);
                } catch (MalformedURLException e) {
                    continue;
                }
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        List<URL> allResources = new LinkedList<URL>();
        List<Jar> allJars = getAllJars();
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(name);
            if (entry != null) {
                allResources.add(new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + name));
            }
        }

        final Iterator<URL> iterator = allResources.iterator();
        return new Enumeration<URL>() {
            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            @Override
            public URL nextElement() {
                return iterator.next();
            }
        };
    }

    private List<Jar> getAllJars() {
        if (allJars == null) {
            jarScanLock.lock();
            try {
                if (allJars == null) {
                    allJars = new LinkedList<Jar>();
                    for (File path : classpath) {
                        if (path.exists() && path.isDirectory()) {
                            String[] jarFileNames = path.list(new FilenameFilter() {
                                @Override
                                public boolean accept(File dir, String name) {
                                    return name.endsWith(".jar");
                                }
                            });
                            for (String fileName : jarFileNames) {
                                try {
                                    File file = new File(path, fileName);
                                    Jar jar = new Jar(new JarFile(file), file);
                                    allJars.add(jar);
                                    logger.info("{} loaded.", file.toString());
                                } catch (IOException e) {
                                    logger.error(e, "{} jar file can't be resolved", fileName);
                                }
                            }
                        }
                    }
                }
            } finally {
                jarScanLock.unlock();
            }
        }

        return allJars;
    }

    private class Jar {
        private JarFile jarFile;
        private File sourceFile;

        private Jar(JarFile jarFile, File sourceFile) {
            this.jarFile = jarFile;
            this.sourceFile = sourceFile;
        }
    }
}
