/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.data;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vaadin.data.util.BeanUtil;
import com.vaadin.server.Setter;
import com.vaadin.shared.util.SharedUtil;
import com.vaadin.util.ReflectTools;

/**
 * A {@link PropertySet} that uses reflection to find bean properties.
 *
 * @author Vaadin Ltd
 *
 * @since 8.0
 *
 * @param <T>
 *            the type of the bean
 */
public class BeanPropertySet<T> implements PropertySet<T> {

    /**
     * Serialized form of a property set. When deserialized, the property set
     * for the corresponding bean type is requested, which either returns the
     * existing cached instance or creates a new one.
     *
     * @see #readResolve()
     * @see BeanPropertyDefinition#writeReplace()
     */
    private static class SerializedPropertySet implements Serializable {
        private final Class<?> beanType;

        private SerializedPropertySet(Class<?> beanType) {
            this.beanType = beanType;
        }

        private Object readResolve() {
            /*
             * When this instance is deserialized, it will be replaced with a
             * property set for the corresponding bean type and property name.
             */
            return get(beanType);
        }
    }

    /**
     * Serialized form of a property definition. When deserialized, the property
     * set for the corresponding bean type is requested, which either returns
     * the existing cached instance or creates a new one. The right property
     * definition is then fetched from the property set.
     *
     * @see #readResolve()
     * @see BeanPropertySet#writeReplace()
     */
    private static class SerializedPropertyDefinition implements Serializable {
        private final Class<?> beanType;
        private final String propertyName;

        private SerializedPropertyDefinition(Class<?> beanType,
                String propertyName) {
            this.beanType = beanType;
            this.propertyName = propertyName;
        }

        private Object readResolve() throws IOException {
            /*
             * When this instance is deserialized, it will be replaced with a
             * property definition for the corresponding bean type and property
             * name.
             */
            return get(beanType).getProperty(propertyName)
                    .orElseThrow(() -> new IOException(
                            beanType + " no longer has a property named "
                                    + propertyName));
        }
    }

    private abstract static class AbstractBeanPropertyDefinition<T, V>
            implements PropertyDefinition<T, V> {
        private final PropertyDescriptor descriptor;
        private final BeanPropertySet<T> propertySet;
        private final Class<?> propertyHolderType;

        public AbstractBeanPropertyDefinition(BeanPropertySet<T> propertySet,
                Class<?> propertyHolderType, PropertyDescriptor descriptor) {
            this.propertySet = propertySet;
            this.propertyHolderType = propertyHolderType;
            this.descriptor = descriptor;

            if (descriptor.getReadMethod() == null) {
                throw new IllegalArgumentException(
                        "Bean property has no accessible getter: "
                                + propertySet.beanType + "."
                                + descriptor.getName());
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<V> getType() {
            return (Class<V>) ReflectTools
                    .convertPrimitiveType(descriptor.getPropertyType());
        }

        @Override
        public String getName() {
            return descriptor.getName();
        }

        @Override
        public String getCaption() {
            return SharedUtil.propertyIdToHumanFriendly(getName());
        }

        @Override
        public BeanPropertySet<T> getPropertySet() {
            return propertySet;
        }

        protected PropertyDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public Class<?> getPropertyHolderType() {
            return propertyHolderType;
        }
    }

    private static class BeanPropertyDefinition<T, V>
            extends AbstractBeanPropertyDefinition<T, V> {

        public BeanPropertyDefinition(BeanPropertySet<T> propertySet,
                Class<T> propertyHolderType, PropertyDescriptor descriptor) {
            super(propertySet, propertyHolderType, descriptor);
        }

        @Override
        public ValueProvider<T, V> getGetter() {
            return bean -> {
                Method readMethod = getDescriptor().getReadMethod();
                Object value = invokeWrapExceptions(readMethod, bean);
                return getType().cast(value);
            };
        }

        @Override
        public Optional<Setter<T, V>> getSetter() {
            if (getDescriptor().getWriteMethod() == null) {
                return Optional.empty();
            }

            Setter<T, V> setter = (bean, value) -> {
                // Do not "optimize" this getter call,
                // if its done outside the code block, that will produce
                // NotSerializableException because of some lambda compilation
                // magic
                Method innerSetter = getDescriptor().getWriteMethod();
                invokeWrapExceptions(innerSetter, bean, value);
            };
            return Optional.of(setter);
        }

        private Object writeReplace() {
            /*
             * Instead of serializing this actual property definition, only
             * serialize a DTO that when deserialized will get the corresponding
             * property definition from the cache.
             */
            return new SerializedPropertyDefinition(getPropertySet().beanType,
                    getName());
        }
    }

    /**
     * Contains properties for a bean type which is nested in another
     * definition.
     *
     * @since 8.1
     * @param <T>
     *            the bean type
     * @param <V>
     *            the value type returned by the getter and set by the setter
     */
    public static class NestedBeanPropertyDefinition<T, V>
            extends AbstractBeanPropertyDefinition<T, V> {

        /**
         * Default maximum depth for scanning nested properties.
         *
         * @since 8.2
         */
        protected static final int MAX_PROPERTY_NESTING_DEPTH = 10;

        /**
         * Class containing the constraints for filtering nested properties.
         *
         * @since 8.2
         *
         */
        protected static class PropertyFilterDefinition
                implements Serializable {
            private int maxNestingDepth;
            private List<String> ignorePackageNamesStartingWith;

            /**
             * Create a property filter with max nesting depth and package names
             * to ignore.
             *
             * @param maxNestingDepth
             *            The maximum amount of nesting levels for
             *            sub-properties.
             * @param ignorePackageNamesStartingWith
             *            Ignore package names that start with this string, for
             *            example "java.lang".
             */
            public PropertyFilterDefinition(int maxNestingDepth,
                    List<String> ignorePackageNamesStartingWith) {
                this.maxNestingDepth = maxNestingDepth;
                this.ignorePackageNamesStartingWith = ignorePackageNamesStartingWith;
            }

            /**
             * Returns the maximum amount of nesting levels for sub-properties.
             *
             * @return maximum nesting depth
             */
            public int getMaxNestingDepth() {
                return maxNestingDepth;
            }

            /**
             * Returns a list of package name prefixes to ignore.
             *
             * @return list of strings that
             */
            public List<String> getIgnorePackageNamesStartingWith() {
                return ignorePackageNamesStartingWith;
            }

            /**
             * Get the default nested property filtering conditions.
             *
             * @return default property filter
             */
            public static PropertyFilterDefinition getDefaultFilter() {
                return new PropertyFilterDefinition(MAX_PROPERTY_NESTING_DEPTH,
                        Arrays.asList("java"));
            }
        }

        private final PropertyDefinition<T, ?> parent;

        private boolean useLongFormName = false;

        public NestedBeanPropertyDefinition(BeanPropertySet<T> propertySet,
                PropertyDefinition<T, ?> parent,
                PropertyDescriptor descriptor) {
            super(propertySet, parent.getType(), descriptor);
            this.parent = parent;
        }

        /**
         * Create nested property definition. Allows use of a long form name.
         *
         * @param propertySet
         *            property set this property belongs is.
         * @param parent
         *            parent property for this nested property
         * @param descriptor
         *            property descriptor
         * @param useLongFormName
         *            use format grandparent.parent.property for name if
         *            {@code true}, needed when creating nested definitions
         *            recursively like in findNestedDefinitions
         * @since 8.2
         */
        public NestedBeanPropertyDefinition(BeanPropertySet<T> propertySet,
                PropertyDefinition<T, ?> parent, PropertyDescriptor descriptor,
                boolean useLongFormName) {
            this(propertySet, parent, descriptor);
            this.useLongFormName = useLongFormName;
        }

        @Override
        public ValueProvider<T, V> getGetter() {
            return bean -> {
                Method readMethod = getDescriptor().getReadMethod();
                Object value = invokeWrapExceptions(readMethod,
                        parent.getGetter().apply(bean));
                return getType().cast(value);
            };
        }

        @Override
        public Optional<Setter<T, V>> getSetter() {
            if (getDescriptor().getWriteMethod() == null) {
                return Optional.empty();
            }

            Setter<T, V> setter = (bean, value) -> {
                // Do not "optimize" this getter call,
                // if its done outside the code block, that will produce
                // NotSerializableException because of some lambda compilation
                // magic
                Method innerSetter = getDescriptor().getWriteMethod();
                invokeWrapExceptions(innerSetter,
                        parent.getGetter().apply(bean), value);
            };
            return Optional.of(setter);
        }

        private Object writeReplace() {
            /*
             * Instead of serializing this actual property definition, only
             * serialize a DTO that when deserialized will get the corresponding
             * property definition from the cache.
             */
            return new SerializedPropertyDefinition(getPropertySet().beanType,
                    parent.getName() + "." + super.getName());
        }

        /**
         * Gets the parent property definition.
         *
         * @return the property definition for the parent
         */
        public PropertyDefinition<T, ?> getParent() {
            return parent;
        }

        @Override
        public String getName() {
            if (useLongFormName) {
                return parent.getName() + "." + super.getName();
            }
            return super.getName();
        }

    }

    /**
     * Key for identifying cached BeanPropertySet instances.
     *
     * @since 8.2
     */
    private static class InstanceKey implements Serializable {
        private Class<?> type;
        private boolean checkNestedDefinitions;
        private int depth;
        private List<String> ignorePackageNames;

        public InstanceKey(Class<?> type, boolean checkNestedDefinitions,
                int depth, List<String> ignorePackageNames) {
            this.type = type;
            this.checkNestedDefinitions = checkNestedDefinitions;
            this.depth = depth;
            this.ignorePackageNames = ignorePackageNames;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (checkNestedDefinitions ? 1231 : 1237);
            result = prime * result + depth;
            result = prime * result + ((ignorePackageNames == null) ? 0
                    : ignorePackageNames.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            InstanceKey other = (InstanceKey) obj;
            if (checkNestedDefinitions != other.checkNestedDefinitions) {
                return false;
            }
            if (depth != other.depth) {
                return false;
            }
            if (ignorePackageNames == null) {
                if (other.ignorePackageNames != null) {
                    return false;
                }
            } else if (!ignorePackageNames.equals(other.ignorePackageNames)) {
                return false;
            }
            if (type == null) {
                if (other.type != null) {
                    return false;
                }
            } else if (!type.equals(other.type)) {
                return false;
            }
            return true;
        }

    }

    private static final ConcurrentMap<InstanceKey, BeanPropertySet<?>> INSTANCES = new ConcurrentHashMap<>();

    private final Class<T> beanType;

    private final Map<String, PropertyDefinition<T, ?>> definitions;

    private BeanPropertySet(Class<T> beanType) {
        this.beanType = beanType;

        try {
            definitions = BeanUtil.getBeanPropertyDescriptors(beanType).stream()
                    .filter(BeanPropertySet::hasNonObjectReadMethod)
                    .map(descriptor -> new BeanPropertyDefinition<>(this,
                            beanType, descriptor))
                    .collect(Collectors.toMap(PropertyDefinition::getName,
                            Function.identity()));
        } catch (IntrospectionException e) {
            throw new IllegalArgumentException(
                    "Cannot find property descriptors for "
                            + beanType.getName(),
                    e);
        }
    }

    private BeanPropertySet(Class<T> beanType, boolean checkNestedDefinitions,
            NestedBeanPropertyDefinition.PropertyFilterDefinition propertyFilterDefinition) {
        this(beanType);
        if (checkNestedDefinitions) {
            Objects.requireNonNull(propertyFilterDefinition,
                    "You must define a property filter callback if using nested property scan.");
            findNestedDefinitions(definitions, 0, propertyFilterDefinition);
        }
    }

    private void findNestedDefinitions(
            Map<String, PropertyDefinition<T, ?>> parentDefinitions, int depth,
            NestedBeanPropertyDefinition.PropertyFilterDefinition filterCallback) {
        if (depth >= filterCallback.getMaxNestingDepth()) {
            return;
        }
        if (parentDefinitions == null) {
            return;
        }
        Map<String, PropertyDefinition<T, ?>> moreProps = new HashMap<>();
        for (String parentPropertyKey : parentDefinitions.keySet()) {
            PropertyDefinition<T, ?> parentProperty = parentDefinitions
                    .get(parentPropertyKey);
            Class<?> type = parentProperty.getType();
            if (type.getPackage() == null || type.isEnum()) {
                continue;
            }
            String packageName = type.getPackage().getName();
            if (filterCallback.getIgnorePackageNamesStartingWith().stream()
                    .anyMatch(prefix -> packageName.startsWith(prefix))) {
                continue;
            }

            try {
                List<PropertyDescriptor> descriptors = BeanUtil
                        .getBeanPropertyDescriptors(type).stream()
                        .filter(BeanPropertySet::hasNonObjectReadMethod)
                        .collect(Collectors.toList());
                for (PropertyDescriptor descriptor : descriptors) {
                    String name = parentPropertyKey + "."
                            + descriptor.getName();
                    PropertyDescriptor subDescriptor = BeanUtil
                            .getPropertyDescriptor(beanType, name);
                    moreProps.put(name, new NestedBeanPropertyDefinition<>(this,
                            parentProperty, subDescriptor, true));

                }
            } catch (IntrospectionException e) {
                throw new IllegalArgumentException(
                        "Error finding nested property descriptors for "
                                + type.getName(),
                        e);
            }
        }
        if (moreProps.size() > 0) {
            definitions.putAll(moreProps);
            findNestedDefinitions(moreProps, ++depth, filterCallback);
        }

    }

    /**
     * Gets a {@link BeanPropertySet} for the given bean type.
     *
     * @param beanType
     *            the bean type to get a property set for, not <code>null</code>
     * @return the bean property set, not <code>null</code>
     */
    @SuppressWarnings("unchecked")
    public static <T> PropertySet<T> get(Class<? extends T> beanType) {
        Objects.requireNonNull(beanType, "Bean type cannot be null");
        InstanceKey key = new InstanceKey(beanType, false, 0, null);
        // Cache the reflection results
        return (PropertySet<T>) INSTANCES.computeIfAbsent(key,
                ignored -> new BeanPropertySet<>(beanType));
    }

    /**
     * Gets a {@link BeanPropertySet} for the given bean type.
     *
     * @param beanType
     *            the bean type to get a property set for, not <code>null</code>
     * @param checkNestedDefinitions
     *            whether to scan for nested definitions in beanType
     * @param filterDefinition
     *            filtering conditions for nested properties
     * @return the bean property set, not <code>null</code>
     * @since 8.2
     */
    @SuppressWarnings("unchecked")
    public static <T> PropertySet<T> get(Class<? extends T> beanType,
            boolean checkNestedDefinitions,
            NestedBeanPropertyDefinition.PropertyFilterDefinition filterDefinition) {
        Objects.requireNonNull(beanType, "Bean type cannot be null");
        InstanceKey key = new InstanceKey(beanType, false,
                filterDefinition.getMaxNestingDepth(),
                filterDefinition.getIgnorePackageNamesStartingWith());
        return (PropertySet<T>) INSTANCES.computeIfAbsent(key,
                k -> new BeanPropertySet<>(beanType, checkNestedDefinitions,
                        filterDefinition));
    }

    @Override
    public Stream<PropertyDefinition<T, ?>> getProperties() {
        return definitions.values().stream();
    }

    @Override
    public Optional<PropertyDefinition<T, ?>> getProperty(String name)
            throws IllegalArgumentException {
        Optional<PropertyDefinition<T, ?>> definition = Optional
                .ofNullable(definitions.get(name));
        if (!definition.isPresent() && name.contains(".")) {
            try {
                String parentName = name.substring(0, name.lastIndexOf('.'));
                Optional<PropertyDefinition<T, ?>> parent = getProperty(
                        parentName);
                if (!parent.isPresent()) {
                    throw new IllegalArgumentException(
                            "Cannot find property descriptor [" + parentName
                                    + "] for " + beanType.getName());
                }

                Optional<PropertyDescriptor> descriptor = Optional.ofNullable(
                        BeanUtil.getPropertyDescriptor(beanType, name));
                if (descriptor.isPresent()) {
                    NestedBeanPropertyDefinition<T, ?> nestedDefinition = new NestedBeanPropertyDefinition<>(
                            this, parent.get(), descriptor.get());
                    definitions.put(name, nestedDefinition);
                    return Optional.of(nestedDefinition);
                } else {
                    throw new IllegalArgumentException(
                            "Cannot find property descriptor [" + name
                                    + "] for " + beanType.getName());
                }

            } catch (IntrospectionException e) {
                throw new IllegalArgumentException(
                        "Cannot find property descriptors for "
                                + beanType.getName(),
                        e);
            }
        }
        return definition;
    }

    private static boolean hasNonObjectReadMethod(
            PropertyDescriptor descriptor) {
        Method readMethod = descriptor.getReadMethod();
        return readMethod != null
                && readMethod.getDeclaringClass() != Object.class;
    }

    private static Object invokeWrapExceptions(Method method, Object target,
            Object... parameters) {
        try {
            return method.invoke(target, parameters);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "Property set for bean " + beanType.getName();
    }

    private Object writeReplace() {
        /*
         * Instead of serializing this actual property set, only serialize a DTO
         * that when deserialized will get the corresponding property set from
         * the cache.
         */
        return new SerializedPropertySet(beanType);
    }
}
