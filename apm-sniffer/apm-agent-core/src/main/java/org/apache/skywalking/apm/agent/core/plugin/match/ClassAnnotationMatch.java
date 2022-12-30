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


package org.apache.skywalking.apm.agent.core.plugin.match;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Match the class by the given annotations in class.
 *
 * @author wusheng
 *
 * Note: 根据标注在类上的注解匹配目标类
 */
public class ClassAnnotationMatch implements IndirectMatch {
    private String[] annotations;

    private ClassAnnotationMatch(String[] annotations) {
        if (annotations == null || annotations.length == 0) {
            throw new IllegalArgumentException("annotations is null");
        }
        this.annotations = annotations;
    }

    /**
     * 为每一个注解创建相应的Junction并将它们以and形式连接起来并返回
     * @return
     */
    @Override
    public ElementMatcher.Junction buildJunction() {
        ElementMatcher.Junction junction = null;
        // 遍历全部注解
        for (String annotation : annotations) {
            if (junction == null) {
                // 该Junction用于检测类是否标注了指定注解
                junction = buildEachAnnotation(annotation);
            } else {
                // 使用and方式将所有Junction对象连接起来
                junction = junction.and(buildEachAnnotation(annotation));
            }
        }
        junction = junction.and(not(isInterface())); // 排除接口
        return junction;
    }

    /**
     * 须包含所有指定注解的类
     * @param typeDescription
     * @return
     */
    @Override
    public boolean isMatch(TypeDescription typeDescription) {
        List<String> annotationList = new ArrayList<String>(Arrays.asList(annotations));
        // 获取类上的注解
        AnnotationList declaredAnnotations = typeDescription.getDeclaredAnnotations();
        // 匹配一个删除一个
        for (AnnotationDescription annotation : declaredAnnotations) {
            annotationList.remove(annotation.getAnnotationType().getActualName());
        }
        // 删空了，就匹配成功了
        if (annotationList.isEmpty()) {
            return true;
        }
        return false;
    }

    private ElementMatcher.Junction buildEachAnnotation(String annotationName) {
        return isAnnotatedWith(named(annotationName));
    }

    public static ClassMatch byClassAnnotationMatch(String[] annotations) {
        return new ClassAnnotationMatch(annotations);
    }
}
