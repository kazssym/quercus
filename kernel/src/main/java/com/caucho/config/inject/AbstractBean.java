/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.config.inject;

import com.caucho.config.annotation.ServiceType;
import com.caucho.config.program.FieldComponentProgram;
import com.caucho.config.*;
import com.caucho.config.j2ee.*;
import com.caucho.config.program.Arg;
import com.caucho.config.program.ConfigProgram;
import com.caucho.config.program.ContainerProgram;
import com.caucho.config.scope.ScopeContext;
import com.caucho.config.types.*;
import com.caucho.naming.*;
import com.caucho.util.*;
import com.caucho.config.*;
import com.caucho.config.bytecode.*;
import com.caucho.config.cfg.*;
import com.caucho.config.event.ObserverImpl;
import com.caucho.config.inject.BeanTypeImpl;

import java.lang.reflect.*;
import java.lang.annotation.*;
import java.util.*;
import java.util.logging.*;
import java.io.Serializable;

import javax.annotation.*;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.ScopeType;
import javax.enterprise.event.IfExists;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.AnnotationLiteral;
import javax.enterprise.inject.BindingType;
import javax.enterprise.inject.Current;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Initializer;
import javax.enterprise.inject.Named;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.Stereotype;
import javax.enterprise.inject.deployment.DeploymentType;
import javax.enterprise.inject.deployment.Production;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.interceptor.InterceptorBindingType;

/**
 * Configuration for the xml web bean component.
 */
abstract public class AbstractBean<T> extends CauchoBean<T>
  implements ObjectProxy
{
  private static final L10N L = new L10N(AbstractBean.class);
  private static final Logger log
    = Logger.getLogger(AbstractBean.class.getName());

  private static final Object []NULL_ARGS = new Object[0];
  private static final ConfigProgram []NULL_INJECT = new ConfigProgram[0];

  private static final HashSet<Class> _reservedTypes
    = new HashSet<Class>();

  public static final Annotation []CURRENT_ANN
    = new Annotation[] { new CurrentLiteral() };

  protected InjectManager _beanManager;
  
  private Type _targetType;
  private BaseType _baseType;
  
  private Class<? extends Annotation> _deploymentType;

  private LinkedHashSet<BaseType> _types
    = new LinkedHashSet<BaseType>();

  private LinkedHashSet<Type> _typeClasses
    = new LinkedHashSet<Type>();

  private String _name;
  
  private ArrayList<Annotation> _bindings
    = new ArrayList<Annotation>();

  private ArrayList<Annotation> _interceptorBindings;

  private Class<? extends Annotation> _scopeType;

  private ArrayList<Annotation> _stereotypes
    = new ArrayList<Annotation>();

  // general custom annotations
  private HashMap<Class,Annotation> _annotationMap
    = new HashMap<Class,Annotation>();
  
  private HashMap<Method,ArrayList<WbInterceptor>> _interceptorMap;

  private ArrayList<ProducesBean> _producesList;

  private boolean _isNullable;

  protected ScopeContext _scope;

  protected ConfigProgram []_injectProgram = NULL_INJECT;
  protected ConfigProgram []_initProgram = NULL_INJECT;
  protected ConfigProgram []_destroyProgram = NULL_INJECT;

  protected Method _cauchoPostConstruct;
  
  public AbstractBean(InjectManager manager)
  {
    _beanManager = manager;
  }

  public InjectManager getWebBeans()
  {
    return _beanManager;
  }

  /**
   * Sets the component type.
   */
  public void setDeploymentType(Class<? extends Annotation> type)
  {
    if (type == null)
      throw new NullPointerException();

    if (! type.isAnnotationPresent(DeploymentType.class))
      throw new ConfigException(L.l("'{0}' is an invalid deployment type because it does not implement @javax.enterprise.inject.DeploymentType",
				    type));

    if (_deploymentType != null && ! _deploymentType.equals(type))
      throw new ConfigException(L.l("deployment-type must be unique"));
    
    _deploymentType = type;
  }

  /**
   * Returns the bean's deployment type
   */
  public Class<? extends Annotation> getDeploymentType()
  {
    return _deploymentType;
  }

  public BeanManager getManager()
  {
    return _beanManager;
  }

  public void setTargetType(Type type)
  {
    _targetType = type;

    _baseType = BaseType.create(type, null);
    
    validateClass(_baseType.getRawClass());
  }
  
  public Type getTargetType()
  {
    return _targetType;
  }
  
  public String getTargetSimpleName()
  {
    if (_targetType instanceof Class)
      return ((Class) _targetType).getSimpleName();
    else
      return String.valueOf(_targetType);
  }
  
  public Class getTargetClass()
  {
    return _baseType.getRawClass();
  }

  protected AnnotatedType getAnnotatedType()
  {
    return new BeanTypeImpl(getTargetType(), getIntrospectionClass());
  }
  
  protected Class getIntrospectionClass()
  {
    return getTargetClass();
  }

  public String getTargetName()
  {
    if (_targetType instanceof Class)
      return ((Class) _targetType).getName();
    else
      return String.valueOf(_targetType);
  }

  /**
   * Returns the bean's EL binding name.
   */
  public void setName(String name)
  {
    _name = name;
  }

  /**
   * Gets the bean's EL binding name.
   */
  public String getName()
  {
    return _name;
  }

  /**
   * Adds a binding annotation
   */
  public void addBinding(Annotation binding)
  {
    if (! binding.annotationType().isAnnotationPresent(BindingType.class))
      throw new ConfigException(L.l("'{0}' is not a valid binding because it does not have a @javax.webbeans.BindingType annotation",
				    binding));
    
    _bindings.add(binding);
  }

  /**
   * Returns the bean's binding types
   */
  public Set<Annotation> getBindings()
  {
    Set<Annotation> set = new LinkedHashSet<Annotation>();

    for (Annotation binding : _bindings) {
      set.add(binding);
    }

    return set;
  }

  /**
   * Returns an array of the binding annotations
   */
  public Annotation []getBindingArray()
  {
    if (_bindings == null || _bindings.size() == 0)
      return new Annotation[] { new CurrentLiteral() };

    Annotation []bindings = new Annotation[_bindings.size()];
    _bindings.toArray(bindings);
    
    return bindings;
  }

  /**
   * Adds a binding annotation
   */
  public void addInterceptorBinding(Annotation binding)
  {
    if (! binding.annotationType().isAnnotationPresent(InterceptorBindingType.class))
      throw new ConfigException(L.l("'{0}' is not a valid binding because it does not have a @javax.webbeans.InterceptorBindingType annotation",
				    binding));
    if (_interceptorBindings == null)
      _interceptorBindings = new ArrayList<Annotation>();
    
    _interceptorBindings.add(binding);
  }

  /**
   * Returns the bean's binding types
   */
  public Set<Annotation> getInterceptorBindingTypes()
  {
    Set<Annotation> set = new LinkedHashSet<Annotation>();

    for (Annotation binding : _interceptorBindings) {
      set.add(binding);
    }

    return set;
  }

  /**
   * Returns an array of the binding annotations
   */
  public Annotation []getInterceptorBindingArray()
  {
    if (_interceptorBindings == null)
      return null;

    Annotation []bindings = new Annotation[_interceptorBindings.size()];
    _interceptorBindings.toArray(bindings);
    
    return bindings;
  }

  /**
   * Sets the scope annotation.
   */
  public void setScopeType(Class<? extends Annotation> scopeType)
  {
    if (! scopeType.isAnnotationPresent(ScopeType.class))
      throw new ConfigException(L.l("'{0}' is not a valid scope because it does not have a @javax.webbeans.ScopeType annotation",
				    scopeType));

    if (_scopeType != null && ! _scopeType.equals(scopeType))
      throw new ConfigException(L.l("'{0}' conflicts with an earlier scope type definition '{1}'.  ScopeType must be defined exactly once.",
				    scopeType.getName(),
				    _scopeType.getName()));
    
    _scopeType = scopeType;
  }

  /**
   * Returns the scope
   */
  public Class<? extends Annotation> getScopeType()
  {
    return _scopeType;
  }

  /**
   * Adds a stereotype
   */
  public void addStereotype(Annotation stereotype)
  {
    if (! stereotype.annotationType().isAnnotationPresent(Stereotype.class))
      throw new ConfigException(L.l("'{0}' is not a valid stereotype because it does not have a @javax.webbeans.Stereotype annotation",
				    stereotype));
    
    _stereotypes.add(stereotype);
  }

  /**
   * Adds a custom
   */
  public void addAnnotation(Annotation annotation)
  {
    _annotationMap.put(annotation.annotationType(), annotation);
  }

  /**
   * Returns the services
   */
  public Annotation []getAnnotations()
  {
    Annotation []annotations = new Annotation[_annotationMap.size()];

    _annotationMap.values().toArray(annotations);
    
    return annotations;
  }

  public Annotation getAnnotation(Class type)
  {
    return _annotationMap.get(type);
  }

  public boolean isAnnotationPresent(Class type)
  {
    return _annotationMap.get(type) != null;
  }

  /**
   * Returns the types that the bean implements
   */
  public Set<Type> getTypes()
  {
    return _typeClasses;
  }

  /**
   * Returns the types that the bean implements
   */
  public Set<BaseType> getGenericTypes()
  {
    return _types;
  }

  /**
   * Initialization.
   */
  protected void init()
  {
    introspect();

    initStereotypes();

    initDefault();

    if (_producesList != null) {
      for (ProducesBean producesBean : _producesList) {
	_beanManager.addBean(producesBean);
      }
    }

    Collections.sort(_bindings, new AnnotationComparator());
  }

  protected void initDefault()
  {
    if (_deploymentType == null)
      _deploymentType = Production.class;

    if (_bindings.size() == 0)
      addBinding(CurrentLiteral.CURRENT);

    if (_scopeType == null)
      _scopeType = Dependent.class;

    if ("".equals(_name)) {
      _name = getDefaultName();
    }
  }

  protected String getDefaultName()
  {
    String name = getTargetSimpleName();

    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  protected void introspect()
  {
    Class cl = getIntrospectionClass();

    introspect(cl);
  }

  protected void introspect(Class cl)
  {
    if (_types.size() == 0)
      introspectTypes(cl);
  }

  /**
   * Introspects all the types implemented by the class
   */
  protected void introspectTypes(Type type)
  {
    if (_types.size() == 0)
      introspectTypes(type, null);
  }

  /**
   * Introspects all the types implemented by the class
   */
  private void introspectTypes(Type type, HashMap paramMap)
  {
    if (type == null || _reservedTypes.contains(type))
      return;

    Class cl = addType(type, paramMap);
    
    if (cl == null)
      return;
    
    introspectTypes(cl.getGenericSuperclass(), paramMap);

    for (Type iface : cl.getGenericInterfaces()) {
      introspectTypes(iface, paramMap);
    }
  }

  protected Class addType(Type type)
  {
    return addType(type, null);
  }

  protected Class addType(Type type, HashMap paramMap)
  {
    BaseType baseType = BaseType.create(type, paramMap);

    if (baseType == null)
      return null;

    if (_types.contains(baseType))
      return null;
    
    _types.add(baseType);

    /*
    if (! _typeClasses.contains(baseType.getRawClass()))
      _typeClasses.add(baseType.getRawClass());
    */
    if (! _typeClasses.contains(baseType.toType()))
      _typeClasses.add(baseType.toType());

    return baseType.getRawClass();
  }

  protected void introspectAnnotations(Set<Annotation> annotations)
  {
    introspectDeploymentType(annotations);
    introspectScope(annotations);
    introspectBindings(annotations);
    introspectStereotypes(annotations);

    for (Annotation ann : annotations) {
      if (_annotationMap.get(ann.annotationType()) == null)
	_annotationMap.put(ann.annotationType(), ann);
    }
  }

  /**
   * Called for implicit introspection.
   */
  protected void introspectDeploymentType(Set<Annotation> annotations)
  {
    Class deploymentType = null;

    if (getDeploymentType() == null) {
      for (Annotation ann : annotations) {
	if (ann.annotationType().isAnnotationPresent(DeploymentType.class)) {
	  if (deploymentType != null)
	    throw new ConfigException(L.l("{0}: @DeploymentType annotation @{1} is invalid because it conflicts with @{2}.  Java Injection components may only have a single @DeploymentType.",
					  getTargetName(),
					  deploymentType.getName(),
					  ann.annotationType().getName()));

	  deploymentType = ann.annotationType();
	  setDeploymentType(deploymentType);
	}
      }
    }

    if (getDeploymentType() == null)
      setDeploymentType(Production.class);
  }

  /**
   * Called for implicit introspection.
   */
  protected void introspectScope(Set<Annotation> annotations)
  {
    Class scopeClass = null;

    if (getScopeType() == null) {
      for (Annotation ann : annotations) {
	if (ann.annotationType().isAnnotationPresent(ScopeType.class)) {
	  if (scopeClass != null)
	    throw new ConfigException(L.l("{0}: @ScopeType annotation @{1} conflicts with @{2}.  Java Injection components may only have a single @ScopeType.",
					  getTargetName(),
					  scopeClass.getName(),
					  ann.annotationType().getName()));

	  scopeClass = ann.annotationType();
	  setScopeType(scopeClass);
	}
      }
    }

    if (getScopeType() == null)
      setScopeType(Dependent.class);
  }

  /**
   * Introspects the binding annotations
   */
  protected void introspectBindings(Set<Annotation> annotations)
  {
    if (_bindings.size() == 0) {
      for (Annotation ann : annotations) {
	if (ann.annotationType().isAnnotationPresent(BindingType.class)) {
	  addBinding(ann);
	}
      }
    }

    if (_bindings.size() == 0)
      addBinding(CurrentLiteral.CURRENT);
  }

  /**
   * Introspects the binding annotations
   */
  protected void introspectName(AnnotatedType beanType)
  {
    if (getName() == null) {
      Annotation ann = beanType.getAnnotation(Named.class);
      
      if (ann != null) {
	String value = null;
	
	try {
	  // ioc/0m04
	  Method m = ann.getClass().getMethod("value", new Class[0]);
	  value = (String) m.invoke(ann);
	} catch (Exception e) {
	  log.log(Level.FINE, e.toString(), e);
	}

	if (value == null)
	  value = "";
	  
	setName(value);
      }
    }

    if ("".equals(getName())) {
      String name = beanType.getJavaClass().getSimpleName();

      name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
	
      setName(name);
    }
  }

  /**
   * Adds the stereotypes from the bean's annotations
   */
  protected void introspectStereotypes(Set<Annotation> annotations)
  {
    if (_stereotypes.size() == 0) {
      for (Annotation ann : annotations) {
	Class annType = ann.annotationType();
      
	if (annType.isAnnotationPresent(Stereotype.class)) {
	  _stereotypes.add(ann);
	}
      }
    }
  }

  /**
   * Adds any values from the stereotypes
   */
  protected void initStereotypes()
  {
    if (_scopeType == null) {
      for (Annotation stereotype : _stereotypes) {
	Class stereotypeType = stereotype.annotationType();
	
	for (Annotation ann : stereotypeType.getDeclaredAnnotations()) {
	  Class annType = ann.annotationType();
	  
	  if (annType.isAnnotationPresent(ScopeType.class))
	    setScopeType(annType);
	}
      }
    }
    
    if (_deploymentType == null) {
      for (Annotation stereotype : _stereotypes) {
	Class stereotypeType = stereotype.annotationType();
	
	for (Annotation ann : stereotypeType.getDeclaredAnnotations()) {
	  Class annType = ann.annotationType();

	  if (! annType.isAnnotationPresent(DeploymentType.class))
	    continue;

	  // XXX: potential issue where getDeploymentPriority isn't set yet
	  
	  if (_deploymentType == null
	      || (_beanManager.getDeploymentPriority(_deploymentType)
		  < _beanManager.getDeploymentPriority(annType))) {
	    _deploymentType = annType;
	  }
	}
      }
    }
    
    if (_name == null) {
      for (Annotation stereotype : _stereotypes) {
	Class stereotypeType = stereotype.annotationType();
	
	for (Annotation ann : stereotypeType.getDeclaredAnnotations()) {
	  Class annType = ann.annotationType();
	  
	  if (annType.equals(Named.class)) {
	    Named named = (Named) ann;
	    _name = "";

	    if (! "".equals(named.value()))
	      throw new ConfigException(L.l("@Named must not have a value in a @Stereotype definition, because @Stereotypes are used with multiple beans."));
	  }
	}
      }
    }
    
    for (Annotation stereotypeAnn : _stereotypes) {
      Class stereotypeType = stereotypeAnn.annotationType();

      for (Annotation ann : stereotypeType.getDeclaredAnnotations()) {
	Class annType = ann.annotationType();

	if (annType.isAnnotationPresent(BindingType.class)) {
	  throw new ConfigException(L.l("'{0}' is not allowed on @Stereotype '{1}' because stereotypes may not have @BindingType annotations",
					ann, stereotypeAnn));
	}

	if (ann instanceof Stereotype) {
	  Stereotype stereotype = (Stereotype) ann;
      
	  for (Class requiredType : stereotype.requiredTypes()) {
	    if (! requiredType.isAssignableFrom(getTargetClass())) {
	      throw new ConfigException(L.l("'{0}' may not have '{1}' because it does not implement the required type '{2}'",
					    getTargetName(),
					    stereotypeAnn,
					    requiredType.getName()));
	    }
	  }

	  boolean hasScope = stereotype.supportedScopes().length == 0;
	  for (Class supportedScope : stereotype.supportedScopes()) {
	    Class scopeType = getScopeType();
	    if (scopeType == null)
	      scopeType = Dependent.class;
	    
	    if (supportedScope.equals(scopeType))
	      hasScope = true;
	  }

	  if (! hasScope) {
	    ArrayList<String> scopeNames = new ArrayList<String>();

	    for (Class supportedScope : stereotype.supportedScopes())
	      scopeNames.add("@" + supportedScope.getSimpleName());
	    
	    throw new ConfigException(L.l("'{0}' may not have '{1}' because it does not implement a supported scope {2}",
					  getTargetName(),
					  stereotypeAnn,
					  scopeNames));
	  }
	}
      }
    }
  }

  /**
   * Introspects the methods for any @Produces
   */
  protected void introspectProduces(AnnotatedType<?> beanType)
  {
    for (AnnotatedMethod beanMethod : beanType.getMethods()) {
      if (beanMethod.isAnnotationPresent(Produces.class))
	addProduces(beanMethod);
    }
  }

  protected void addProduces(AnnotatedMethod beanMethod)
  {
    Arg []args = new Arg[0];
    
    ProducesBean bean = ProducesBean.create(_beanManager, this, beanMethod,
					    args);

    bean.init();

    if (_producesList == null)
      _producesList = new ArrayList<ProducesBean>();
    
    _producesList.add(bean);
  }

  protected void addMethod(Method method, Annotation []annList)
  {
    SimpleBeanMethod beanMethod = new SimpleBeanMethod(method, annList);
  }

  /**
   * Introspects any observers.
   */
  protected void introspectObservers(AnnotatedType<?> beanType)
  {
  }

  /**
   * Introspects any intercepted methods
   */
  protected void introspectInterceptors(Class cl)
  {
    introspectInterceptors(cl.getMethods());
  }
  
  /**
   * Introspects any intercepted methods
   */
  protected void introspectInterceptors(Method []methods)
  {
    for (Method method : methods) {
      if (method.getDeclaringClass().equals(Object.class))
	continue;
      
      ArrayList<Annotation> interceptorTypes = findInterceptorTypes(method);

      if (interceptorTypes == null)
	continue;

      /* XXX:
      ArrayList<WbInterceptor> interceptors
	= _webBeans.findInterceptors(interceptorTypes);

      if (interceptors != null) {
	if (_interceptorMap == null)
	  _interceptorMap = new HashMap<Method,ArrayList<WbInterceptor>>();

	_interceptorMap.put(method, interceptors);
      }
      */
    }
  }

  private ArrayList<Annotation> findInterceptorTypes(Method method)
  {
    ArrayList<Annotation> types = null;

    for (Annotation ann : method.getAnnotations()) {
      if (ann.annotationType().isAnnotationPresent(InterceptorBindingType.class)) {
	if (types == null)
	  types = new ArrayList<Annotation>();

	types.add(ann);
      }
    }

    return types;
  }
  
  protected boolean hasBindingAnnotation(AnnotatedConstructor<?> ctor)
  {
    if (ctor.isAnnotationPresent(Initializer.class))
      return true;

    for (AnnotatedParameter param : ctor.getParameters()) {
      for (Annotation ann : param.getAnnotations()) {
	if (ann.annotationType().isAnnotationPresent(BindingType.class))
	  return true;
      }
    }

    return false;
  }

  private <X> int findObserverAnnotation(AnnotatedMethod<X> method)
  {
    List<AnnotatedParameter<X>> params = method.getParameters();
    int size = params.size();
    int observer = -1;

    for (int i = 0; i < size; i++) {
      AnnotatedParameter param = params.get(i);
      
      for (Annotation ann : param.getAnnotations()) {
	if (ann instanceof Observes) {
	  if (observer >= 0)
	    throw InjectManager.error(method.getJavaMember(), L.l("Only one param may have an @Observer"));
	  
	  observer = i;
	}
      }
    }

    return observer;
  }

  public boolean isMatch(ArrayList<Annotation> bindings)
  {
    for (int i = 0; i < bindings.size(); i++) {
      if (! isMatch(bindings.get(i)))
	return false;
    }
    
    return true;
  }

  public boolean isMatch(Annotation []bindings)
  {
    for (Annotation binding : bindings) {
      if (! isMatch(binding))
	return false;
    }
    
    return true;
  }

  /**
   * Returns true if at least one of this component's bindings match
   * the injection binding.
   */
  public boolean isMatch(Annotation bindAnn)
  {
    for (int i = 0; i < _bindings.size(); i++) {
      // XXX:
      if (_bindings.get(i).equals(bindAnn))
	return true;
    }
    
    return false;
  }

  /**
   * Returns the bean's name or null if the bean does not have a
   * primary name.
   */
      /*
  public String getName()
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
      */

  /**
   * Returns true if the bean can be null
   */
  public boolean isNullable()
  {
    return false;
  }

  /**
   * Returns true if the bean is serializable
   */
  public boolean isPassivationCapable()
  {
    return Serializable.class.isAssignableFrom(getTargetClass());
  }

  protected Bean bindParameter(String loc,
			       Type type,
			       Set<Annotation> bindings)
  {
    Set set = _beanManager.resolve(type, bindings);

    if (set == null || set.size() == 0)
      return null;

    if (set.size() > 1) {
      throw new ConfigException(L.l("{0}: can't bind webbeans '{1}' because multiple matching beans were found: {2}",
				    loc, type, set));
    }

    Iterator iter = set.iterator();
    if (iter.hasNext()) {
      Bean bean = (Bean) iter.next();

      return bean;
    }

    return null;
  }

  protected void validateClass(Class cl)
  {
    ClassLoader beanManagerLoader = _beanManager.getClassLoader();
    
    if (beanManagerLoader == null)
      beanManagerLoader = ClassLoader.getSystemClassLoader();

    ClassLoader beanLoader = cl.getClassLoader();

    if (beanLoader == null)
      beanLoader = ClassLoader.getSystemClassLoader();

    for (ClassLoader loader = beanManagerLoader;
	 loader != null;
	 loader = loader.getParent()) {
      if (beanLoader == loader)
	return;
    }

    if (false) {
      // server/2pad
      throw new IllegalStateException(L.l("'{0}' is an invalid class because its classloader '{1}' does not belong to the webbeans classloader '{2}'",
					  cl, beanLoader,
					  beanManagerLoader));
    }
    else {
      log.fine(L.l("'{0}' may be incorrect classloader '{1}' does not belong to the injection classloader '{2}'",
		   cl, beanLoader,
		   beanManagerLoader));
    }
  }
  
  /**
   * Instantiate the bean.
   */
  public T instantiate()
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
  
  /**
   * Inject the bean.
   */
  public void inject(T instance)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
  
  /**
   * Call post-construct
   */
  public void postConstruct(T instance)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
  
  /**
   * Call pre-destroy
   */
  public void preDestroy(T instance)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
  
  /**
   * Call destroy
   */
  public void destroy(T instance)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }

  /**
   * Returns the set of injection points, for validation.
   */
  public Set<InjectionPoint> getInjectionPoints()
  {
    throw new UnsupportedOperationException(getClass().getName());
  }

  public String toDebugString()
  {
    StringBuilder sb = new StringBuilder();

    sb.append(getTargetSimpleName());
    sb.append("[");
    
    if (_name != null) {
      sb.append("name=");
      sb.append(_name);
    }

    for (Annotation binding : _bindings) {
      sb.append(",");
      sb.append(binding);
    }

    if (_deploymentType != null) {
      sb.append(", @");
      sb.append(_deploymentType.getSimpleName());
    }
    
    if (_scopeType != null) {
      sb.append(", @");
      sb.append(_scopeType.getSimpleName());
    }

    sb.append("]");

    return sb.toString();
  }

  public String toString()
  {
    StringBuilder sb = new StringBuilder();

    sb.append(getClass().getSimpleName());
    sb.append("[");

    sb.append(getTargetSimpleName());
    sb.append(", {");

    for (int i = 0; i < _bindings.size(); i++) {
      Annotation ann = _bindings.get(i);

      if (i != 0)
	sb.append(", ");

      sb.append(ann);
    }

    sb.append("}, ");

    if (_deploymentType != null) {
      sb.append("@");
      sb.append(_deploymentType.getSimpleName());
    }
    else
      sb.append("@null");
    
    if (_name != null) {
      sb.append(", ");
      sb.append("name=");
      sb.append(_name);
    }
    
    if (_scope != null) {
      sb.append(", @");
      sb.append(_scope.getClass().getSimpleName());
    }

    sb.append("]");

    return sb.toString();
  }

  static class MethodNameComparator implements Comparator<AnnotatedMethod> {
    public int compare(AnnotatedMethod a, AnnotatedMethod b)
    {
      return a.getJavaMember().getName().compareTo(b.getJavaMember().getName());
    }
  }

  static class AnnotationComparator implements Comparator<Annotation> {
    public int compare(Annotation a, Annotation b)
    {
      Class annTypeA = a.annotationType();
      Class annTypeB = b.annotationType();
      
      return annTypeA.getName().compareTo(annTypeB.getName());
    }
  }

  static {
    _reservedTypes.add(java.io.Closeable.class);
    _reservedTypes.add(java.io.Serializable.class);
    _reservedTypes.add(Cloneable.class);
    _reservedTypes.add(Object.class);
    _reservedTypes.add(Comparable.class);
  }
}
