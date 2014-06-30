/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.ext.proxy.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.client.api.CommonEdmEnabledODataClient;
import org.apache.olingo.client.api.v3.UnsupportedInV3Exception;
import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.domain.CommonODataEntity;
import org.apache.olingo.commons.api.domain.CommonODataProperty;
import org.apache.olingo.commons.api.domain.ODataLink;
import org.apache.olingo.commons.api.domain.ODataPrimitiveValue;
import org.apache.olingo.commons.api.domain.ODataValue;
import org.apache.olingo.commons.api.domain.v4.ODataAnnotatable;
import org.apache.olingo.commons.api.domain.v4.ODataAnnotation;
import org.apache.olingo.commons.api.domain.v4.ODataEnumValue;
import org.apache.olingo.commons.api.domain.v4.ODataObjectFactory;
import org.apache.olingo.commons.api.edm.EdmElement;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.EdmTerm;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.constants.ODataServiceVersion;
import org.apache.olingo.commons.core.domain.v4.ODataAnnotationImpl;
import org.apache.olingo.commons.core.edm.EdmTypeInfo;
import org.apache.olingo.commons.core.edm.primitivetype.EdmPrimitiveTypeFactory;
import org.apache.olingo.ext.proxy.api.AbstractTerm;
import org.apache.olingo.ext.proxy.api.annotations.ComplexType;
import org.apache.olingo.ext.proxy.api.annotations.CompoundKeyElement;
import org.apache.olingo.ext.proxy.api.annotations.EnumType;
import org.apache.olingo.ext.proxy.api.annotations.Key;
import org.apache.olingo.ext.proxy.api.annotations.Namespace;
import org.apache.olingo.ext.proxy.api.annotations.Property;
import org.apache.olingo.ext.proxy.api.annotations.Term;
import org.apache.olingo.ext.proxy.commons.AbstractStructuredInvocationHandler;
import org.apache.olingo.ext.proxy.commons.ComplexInvocationHandler;
import org.apache.olingo.ext.proxy.commons.EntityInvocationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreUtils {

  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(CoreUtils.class);

  private CoreUtils() {
    // Empty private constructor for static utility classes
  }

  public static ODataValue getODataValue(
          final CommonEdmEnabledODataClient<?> client, final EdmTypeInfo type, final Object obj) {

    final ODataValue value;

    if (type.isCollection()) {
      value = client.getObjectFactory().newCollectionValue(type.getFullQualifiedName().toString());

      final EdmTypeInfo intType = new EdmTypeInfo.Builder().
              setEdm(client.getCachedEdm()).setTypeExpression(type.getFullQualifiedName().toString()).build();

      for (Object collectionItem : (Collection<?>) obj) {
        if (intType.isPrimitiveType()) {
          value.asCollection().add(getODataValue(client, intType, collectionItem).asPrimitive());
        } else if (intType.isComplexType()) {
          value.asCollection().add(getODataValue(client, intType, collectionItem).asComplex());
        } else if (intType.isEnumType()) {
          if (client.getServiceVersion().compareTo(ODataServiceVersion.V30) <= 0) {
            throw new UnsupportedInV3Exception();
          } else {
            value.asCollection().add(((org.apache.olingo.commons.api.domain.v4.ODataValue) getODataValue(
                    client, intType, collectionItem)).asEnum());
          }

        } else {
          throw new UnsupportedOperationException("Unsupported object type " + intType.getFullQualifiedName());
        }
      }
    } else if (type.isComplexType()) {
      final Object objHandler = Proxy.getInvocationHandler(obj);
      if (objHandler instanceof ComplexInvocationHandler) {
        value = ((ComplexInvocationHandler) objHandler).getComplex();

        final Class<?> typeRef = ((ComplexInvocationHandler) objHandler).getTypeRef();
        for (Method method : typeRef.getMethods()) {
          final Property propAnn = method.getAnnotation(Property.class);
          if (propAnn != null) {
            try {
              value.asComplex().add(getODataComplexProperty(
                      client, type.getFullQualifiedName(), propAnn.name(), method.invoke(obj)));
            } catch (Exception ignore) {
              // ignore value
              LOG.warn("Error attaching complex {} for field '{}.{}'",
                      type.getFullQualifiedName(), typeRef.getName(), propAnn.name(), ignore);
            }
          }
        }
      } else {
        throw new IllegalArgumentException(objHandler.getClass().getName() + "' is not a complex value");
      }
    } else if (type.isEnumType()) {
      if (client.getServiceVersion().compareTo(ODataServiceVersion.V30) <= 0) {
        throw new UnsupportedInV3Exception();
      } else {
        value = ((org.apache.olingo.commons.api.domain.v4.ODataObjectFactory) client.getObjectFactory()).
                newEnumValue(type.getFullQualifiedName().toString(), ((Enum<?>) obj).name());
      }
    } else {
      value = client.getObjectFactory().newPrimitiveValueBuilder().setType(type.getPrimitiveTypeKind()).setValue(obj).
              build();
    }

    return value;
  }

  private static CommonODataProperty getODataEntityProperty(
          final CommonEdmEnabledODataClient<?> client,
          final FullQualifiedName entity,
          final String property,
          final Object obj) {

    final EdmElement edmProperty = client.getCachedEdm().getEntityType(entity).getProperty(property);
    return getODataProperty(client, edmProperty, property, obj);
  }

  private static CommonODataProperty getODataComplexProperty(
          final CommonEdmEnabledODataClient<?> client,
          final FullQualifiedName complex,
          final String property,
          final Object obj) {

    final EdmElement edmProperty = client.getCachedEdm().getComplexType(complex).getProperty(property);
    return getODataProperty(client, edmProperty, property, obj);
  }

  private static CommonODataProperty getODataProperty(
          final CommonEdmEnabledODataClient<?> client,
          final EdmElement edmProperty,
          final String property,
          final Object obj) {

    final EdmTypeInfo type;
    if (edmProperty == null) {
      // maybe opentype ...
      type = null;
    } else {
      final EdmType edmType = edmProperty.getType();

      type = new EdmTypeInfo.Builder().setEdm(client.getCachedEdm()).setTypeExpression(
              edmProperty.isCollection()
              ? "Collection(" + edmType.getFullQualifiedName().toString() + ")"
              : edmType.getFullQualifiedName().toString()).build();
    }

    return getODataProperty(client, property, type, obj);
  }

  public static ODataAnnotation getODataAnnotation(
          final CommonEdmEnabledODataClient<?> client, final String term, final EdmType type, final Object obj) {

    ODataAnnotation annotation;

    if (obj == null) {
      annotation = new ODataAnnotationImpl(term, null);
    } else {
      final EdmTypeInfo valueType = type == null
              ? guessTypeFromObject(client, obj)
              : new EdmTypeInfo.Builder().setEdm(client.getCachedEdm()).
              setTypeExpression(type.getFullQualifiedName().toString()).build();

      annotation = new ODataAnnotationImpl(term,
              (org.apache.olingo.commons.api.domain.v4.ODataValue) getODataValue(client, valueType, obj));
    }

    return annotation;
  }

  public static CommonODataProperty getODataProperty(
          final CommonEdmEnabledODataClient<?> client, final String name, final EdmTypeInfo type, final Object obj) {

    CommonODataProperty property;

    try {
      if (obj == null) {
        property = client.getObjectFactory().newPrimitiveProperty(name, null);
      } else {
        final EdmTypeInfo valueType = type == null
                ? guessTypeFromObject(client, obj)
                : type;
        final ODataValue value = getODataValue(client, valueType, obj);

        if (valueType.isCollection()) {
          property = client.getObjectFactory().newCollectionProperty(name, value.asCollection());
        } else if (valueType.isPrimitiveType()) {
          property = client.getObjectFactory().newPrimitiveProperty(name, value.asPrimitive());
        } else if (valueType.isComplexType()) {
          property = client.getObjectFactory().newComplexProperty(name, value.asComplex());
        } else if (valueType.isEnumType()) {
          if (client.getServiceVersion().compareTo(ODataServiceVersion.V30) <= 0) {
            throw new UnsupportedInV3Exception();
          } else {
            property = ((ODataObjectFactory) client.getObjectFactory()).newEnumProperty(name,
                    ((org.apache.olingo.commons.api.domain.v4.ODataValue) value).asEnum());
          }
        } else {
          throw new UnsupportedOperationException("Usupported object type " + valueType.getFullQualifiedName());
        }
      }

      return property;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static EdmTypeInfo guessTypeFromObject(
          final CommonEdmEnabledODataClient<?> client, final Object obj) {

    final EdmTypeInfo.Builder edmTypeInfo = new EdmTypeInfo.Builder().setEdm(client.getCachedEdm());

    if (Collection.class.isAssignableFrom(obj.getClass())) {
      final EdmTypeInfo type = guessPrimitiveType(client, ClassUtils.extractTypeArg(obj.getClass()));
      return edmTypeInfo.setTypeExpression("Collection(" + type.getFullQualifiedName() + ")").build();
    } else if (obj instanceof Proxy) {
      final Class<?> typeRef = obj.getClass().getInterfaces()[0];
      final String ns = typeRef.getAnnotation(Namespace.class).value();
      final String name = typeRef.getAnnotation(ComplexType.class).name();
      return edmTypeInfo.setTypeExpression(new FullQualifiedName(ns, name).toString()).build();
    } else if (obj.getClass().getAnnotation(EnumType.class) != null) {
      final Class<?> typeRef = obj.getClass();
      final String ns = typeRef.getAnnotation(Namespace.class).value();
      final String name = typeRef.getAnnotation(EnumType.class).name();
      return edmTypeInfo.setTypeExpression(new FullQualifiedName(ns, name).toString()).build();
    } else {
      return guessPrimitiveType(client, obj.getClass());
    }
  }

  private static EdmTypeInfo guessPrimitiveType(final CommonEdmEnabledODataClient<?> client, final Class<?> clazz) {
    EdmPrimitiveTypeKind bckCandidate = null;

    for (EdmPrimitiveTypeKind kind : EdmPrimitiveTypeKind.values()) {
      if (kind.getSupportedVersions().contains(client.getServiceVersion())) {
        final Class<?> target = EdmPrimitiveTypeFactory.getInstance(kind).getDefaultType();

        if (clazz.equals(target)) {
          return new EdmTypeInfo.Builder().setEdm(client.getCachedEdm()).setTypeExpression(kind.toString()).build();
        } else if (target.isAssignableFrom(clazz)) {
          bckCandidate = kind;
        } else if (target == Timestamp.class
            && (kind == EdmPrimitiveTypeKind.DateTime || kind == EdmPrimitiveTypeKind.DateTimeOffset)) {
          bckCandidate = kind;
        }
      }
    }

    if (bckCandidate == null) {
      throw new IllegalArgumentException(clazz.getSimpleName() + " is not a simple type");
    } else {
      return new EdmTypeInfo.Builder().setEdm(client.getCachedEdm()).setTypeExpression(bckCandidate.toString()).build();
    }
  }

  @SuppressWarnings("unchecked")
  public static void addProperties(
          final CommonEdmEnabledODataClient<?> client,
          final Map<String, Object> changes,
          final CommonODataEntity entity) {

    for (Map.Entry<String, Object> entry : changes.entrySet()) {
      ((List<CommonODataProperty>) entity.getProperties()).add(
              getODataEntityProperty(client, entity.getTypeName(), entry.getKey(), entry.getValue()));
    }
  }

  public static void addAnnotations(
          final CommonEdmEnabledODataClient<?> client,
          final Map<Class<? extends AbstractTerm>, Object> annotations,
          final ODataAnnotatable annotatable) {

    for (Map.Entry<Class<? extends AbstractTerm>, Object> entry : annotations.entrySet()) {
      final Namespace nsAnn = entry.getKey().getAnnotation(Namespace.class);
      final Term termAnn = entry.getKey().getAnnotation(Term.class);
      final FullQualifiedName termName = new FullQualifiedName(nsAnn.value(), termAnn.name());
      final EdmTerm term = client.getCachedEdm().getTerm(termName);
      if (term == null) {
        LOG.error("Could not find term for class {}", entry.getKey().getName());
      } else {
        annotatable.getAnnotations().add(getODataAnnotation(
                client, term.getFullQualifiedName().toString(), term.getType(), entry.getValue()));
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Enum<?> enumValueToObject(final ODataEnumValue value, final Class<?> reference) {
    final Namespace namespace = reference.getAnnotation(Namespace.class);
    final EnumType enumType = reference.getAnnotation(EnumType.class);
    if (value.getTypeName().equals(namespace.value() + "." + enumType.name())) {
      return Enum.valueOf((Class<Enum>) reference, value.getValue());
    }

    return null;
  }

  private static Object primitiveValueToObject(final ODataPrimitiveValue value, final Class<?> reference) {
    Object obj;

    try {
      obj = value.toValue() instanceof Timestamp
              ? value.toCastValue(Calendar.class)
              : reference == null
              ? value.toValue()
              : value.toCastValue(reference);
    } catch (EdmPrimitiveTypeException e) {
      LOG.warn("While casting primitive value {} to {}", value, reference, e);
      obj = value.toValue();
    }

    return obj;
  }

  private static void setPropertyValue(final Object bean, final Method getter, final Object value)
          throws NoSuchMethodException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {

    // Assumption: setter is always prefixed by 'set' word
    final String setterName = getter.getName().replaceFirst("get", "set");
    bean.getClass().getMethod(setterName, getter.getReturnType()).invoke(bean, value);
  }

  private static Class<?> getPropertyClass(final Class<?> entityClass, final String propertyName) {
    Class<?> propertyClass = null;
    try {
      final Method getter = entityClass.getMethod("get" + StringUtils.capitalize(propertyName));
      if (getter != null) {
        propertyClass = getter.getReturnType();
      }
    } catch (Exception e) {
      LOG.error("Could not determine the Java type of {}", propertyName, e);
    }
    return propertyClass;
  }

  public static Object getKey(
          final CommonEdmEnabledODataClient<?> client,
          final EntityInvocationHandler typeHandler,
          final Class<?> entityTypeRef,
          final CommonODataEntity entity) {

    Object res = null;

    if (!entity.getProperties().isEmpty()) {
      final Class<?> keyRef = ClassUtils.getCompoundKeyRef(entityTypeRef);
      if (keyRef == null) {
        final CommonODataProperty property = entity.getProperty(firstValidEntityKey(entityTypeRef));
        if (property != null && property.hasPrimitiveValue()) {
          res = primitiveValueToObject(
                  property.getPrimitiveValue(), getPropertyClass(entityTypeRef, property.getName()));
        }
      } else {
        try {
          res = keyRef.newInstance();
          populate(client, typeHandler, res, CompoundKeyElement.class, entity.getProperties().iterator());
        } catch (Exception e) {
          LOG.error("Error population compound key {}", keyRef.getSimpleName(), e);
          throw new IllegalArgumentException("Cannot populate compound key");
        }
      }
    }

    return res;
  }

  private static void populate(
          final CommonEdmEnabledODataClient<?> client,
          final EntityInvocationHandler typeHandler,
          final Object bean,
          final Class<? extends Annotation> getterAnn,
          final Iterator<? extends CommonODataProperty> propItor) {

    if (bean != null) {
      final Class<?> typeRef;
      if (bean instanceof Proxy) {
        final InvocationHandler handler = Proxy.getInvocationHandler(bean);
        if (handler instanceof AbstractStructuredInvocationHandler) {
          typeRef = ((ComplexInvocationHandler) handler).getTypeRef();
        } else {
          throw new IllegalStateException("Invalid bean " + bean);
        }
      } else {
        typeRef = bean.getClass();
      }
      populate(client, typeHandler, bean, typeRef, getterAnn, propItor);
    }
  }

  @SuppressWarnings({"unchecked"})
  private static void populate(
          final CommonEdmEnabledODataClient<?> client,
          final EntityInvocationHandler typeHandler,
          final Object bean,
          final Class<?> typeRef,
          final Class<? extends Annotation> getterAnn,
          final Iterator<? extends CommonODataProperty> propItor) {

    if (bean != null) {
      while (propItor.hasNext()) {
        final CommonODataProperty property = propItor.next();

        final Method getter = ClassUtils.findGetterByAnnotatedName(typeRef, getterAnn, property.getName());

        if (getter == null) {
          LOG.warn("Could not find any property annotated as {} in {}",
                  property.getName(), bean.getClass().getName());
        } else {
          try {
            if (property.hasNullValue()) {
              setPropertyValue(bean, getter, null);
            } else if (property.hasPrimitiveValue()) {
              setPropertyValue(bean, getter, primitiveValueToObject(
                      property.getPrimitiveValue(), getPropertyClass(typeRef, property.getName())));
            } else if (property.hasComplexValue()) {
              final Object complex = Proxy.newProxyInstance(
                      Thread.currentThread().getContextClassLoader(),
                      new Class<?>[] {getter.getReturnType()},
                      ComplexInvocationHandler.getInstance(
                              client, property.getName(), getter.getReturnType(), typeHandler));

              populate(client, typeHandler, complex, Property.class, property.getValue().asComplex().iterator());
              setPropertyValue(bean, getter, complex);
            } else if (property.hasCollectionValue()) {
              final ParameterizedType collType = (ParameterizedType) getter.getGenericReturnType();
              final Class<?> collItemClass = (Class<?>) collType.getActualTypeArguments()[0];

              Collection<Object> collection = (Collection<Object>) getter.invoke(bean);
              if (collection == null) {
                collection = new ArrayList<Object>();
                setPropertyValue(bean, getter, collection);
              }

              final Iterator<ODataValue> collPropItor = property.getValue().asCollection().iterator();
              while (collPropItor.hasNext()) {
                final ODataValue value = collPropItor.next();
                if (value.isPrimitive()) {
                  collection.add(primitiveValueToObject(
                          value.asPrimitive(), getPropertyClass(typeRef, property.getName())));
                } else if (value.isComplex()) {
                  final Object collItem = Proxy.newProxyInstance(
                          Thread.currentThread().getContextClassLoader(),
                          new Class<?>[] {collItemClass},
                          ComplexInvocationHandler.getInstance(
                                  client, property.getName(), collItemClass, typeHandler));

                  populate(client, typeHandler, collItem, Property.class, value.asComplex().iterator());
                  collection.add(collItem);
                }
              }
            }
          } catch (Exception e) {
            LOG.error("Could not set property {} on {}", getter, bean, e);
          }
        }
      }
    }
  }

  public static Object getObjectFromODataValue(
          final CommonEdmEnabledODataClient<?> client,
          final ODataValue value,
          final Type typeRef,
          final EntityInvocationHandler entityHandler)
          throws InstantiationException, IllegalAccessException {

    Class<?> internalRef;
    if (typeRef == null) {
      internalRef = null;
    } else {
      try {
        internalRef = (Class<?>) ((ParameterizedType) typeRef).getActualTypeArguments()[0];
      } catch (ClassCastException e) {
        internalRef = (Class<?>) typeRef;
      }
    }

    final Object res;

    if (value == null) {
      res = null;
    } else if (value.isComplex()) {
      // complex types supports inheritance in V4, best to re-read actual type
      internalRef = getComplexTypeRef(value);
      res = Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {internalRef},
              ComplexInvocationHandler.getInstance(
                      client, value.asComplex(), internalRef, entityHandler));
    } else if (value.isCollection()) {
      final ArrayList<Object> collection = new ArrayList<Object>();

      final Iterator<ODataValue> collPropItor = value.asCollection().iterator();
      while (collPropItor.hasNext()) {
        final ODataValue itemValue = collPropItor.next();
        if (itemValue.isPrimitive()) {
          collection.add(CoreUtils.primitiveValueToObject(itemValue.asPrimitive(), internalRef));
        } else if (itemValue.isComplex()) {
          internalRef = getComplexTypeRef(value);
          final Object collItem = Proxy.newProxyInstance(
                  Thread.currentThread().getContextClassLoader(),
                  new Class<?>[] {internalRef},
                  ComplexInvocationHandler.getInstance(
                          client, itemValue.asComplex(), internalRef, entityHandler));

          collection.add(collItem);
        }
      }

      res = collection;
    } else if (value instanceof ODataEnumValue) {
      if (internalRef == null) {
        internalRef = getEnumTypeRef(value);
      }
      res = enumValueToObject((ODataEnumValue) value, internalRef);
    } else {
      res = primitiveValueToObject(value.asPrimitive(), internalRef);
    }

    return res;
  }

  @SuppressWarnings("unchecked")
  public static Collection<Class<? extends AbstractTerm>> getAnnotationTerms(final List<ODataAnnotation> annotations) {
    final List<Class<? extends AbstractTerm>> res = new ArrayList<Class<? extends AbstractTerm>>();

    for (ODataAnnotation annotation : annotations) {
      res.add((Class<? extends AbstractTerm>) getTermTypeRef(annotation));
    }

    return res;
  }

  private static Class<?> getTermTypeRef(final ODataAnnotation annotation) {
    try {
      final List<String> pkgs = IOUtils.readLines(Thread.currentThread().getContextClassLoader().
              getResourceAsStream("META-INF/" + Constants.PROXY_TERM_CLASS_LIST),
              Constants.UTF8);
      for (String pkg : pkgs) {
        final Class<?> clazz = Class.forName(pkg);
        final Term term = clazz.getAnnotation(Term.class);
        final Namespace ns = clazz.getAnnotation(Namespace.class);

        if (ns != null && term != null
                && annotation.getTerm().equals(new FullQualifiedName(ns.value(), term.name()).toString())) {

          return clazz;
        }
      }
    } catch (Exception e) {
      LOG.warn("Error retrieving class list for {}", Term.class.getName(), e);
    }

    throw new IllegalArgumentException("Could not find Term class for " + annotation.getTerm());
  }

  private static Class<?> getEnumTypeRef(final ODataValue value) {
    return getTypeRef(value, "META-INF/" + Constants.PROXY_ENUM_CLASS_LIST, EnumType.class);
  }

  private static Class<?> getComplexTypeRef(final ODataValue value) {
    return getTypeRef(value, "META-INF/" + Constants.PROXY_COMPLEX_CLASS_LIST, ComplexType.class);
  }

  private static Class<?> getTypeRef(
          final ODataValue value,
          final String proxyClassListFile,
          final Class<? extends Annotation> annType) {

    if (!annType.isAssignableFrom(EnumType.class) && !annType.isAssignableFrom(ComplexType.class)) {
      throw new IllegalArgumentException("Invalid annotation type " + annType);
    }

    try {
      final List<String> pkgs = IOUtils.readLines(
              Thread.currentThread().getContextClassLoader().getResourceAsStream(proxyClassListFile),
              Constants.UTF8);

      for (String pkg : pkgs) {
        final Class<?> clazz = Class.forName(pkg);
        final Annotation ann = clazz.getAnnotation(annType);
        final Namespace ns = clazz.getAnnotation(Namespace.class);

        if (ns != null && ann != null
                && value.getTypeName().replaceAll("^Collection\\(", "").replaceAll("\\)$", "").
                equals(new FullQualifiedName(ns.value(), annType.isAssignableFrom(EnumType.class)
                                ? EnumType.class.cast(ann).name()
                                : ComplexType.class.cast(ann).name()).toString())) {
          return clazz;
        }
      }
    } catch (Exception e) {
      LOG.warn("Error retrieving class list for {}", annType.getName(), e);
    }

    throw new IllegalArgumentException("Provided value '" + value + "' is not annotated as " + annType.getName());
  }

  private static String firstValidEntityKey(final Class<?> entityTypeRef) {
    for (Method method : entityTypeRef.getDeclaredMethods()) {
      if (method.getAnnotation(Key.class) != null) {
        final Annotation ann = method.getAnnotation(Property.class);
        if (ann != null) {
          return ((Property) ann).name();
        }
      }
    }
    return null;
  }

  public static URI getMediaEditLink(final String name, final CommonODataEntity entity) {
    for (ODataLink link : entity.getMediaEditLinks()) {
      if (name.equalsIgnoreCase(link.getName())) {
        return link.getLink();
      }
    }

    throw new IllegalArgumentException("Invalid streamed property " + name);
  }
}
