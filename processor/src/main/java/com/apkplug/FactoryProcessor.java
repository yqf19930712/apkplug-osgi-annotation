/*
 * Copyright (C) 2015 Hannes Dorfmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apkplug;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import static javax.lang.model.util.ElementFilter.methodsIn;
/**
 * Annotation Processor for @Factory annotation
 *
 * @author Hannes Dorfmann
 */
@AutoService(Processor.class) public class FactoryProcessor extends AbstractProcessor {

  public static final String PACKAGE_NAME = "com.apkplug";
  public static final String SIMPLE_BUNDLE = "SimpleBundle";
  public static final String PROXY_SUFFIX = "Proxy";
  private Types typeUtils;
  private Elements elementUtils;
  private Filer filer;
  private Messager messager;
  private Map<String, FactoryGroupedClasses> factoryClasses =
      new LinkedHashMap<String, FactoryGroupedClasses>();

  private Map<String,TypeElement> proxyMap = new LinkedHashMap<String, TypeElement>();
  private Map<String,TypeElement> interfaceMap = new LinkedHashMap<String, TypeElement>();
  private Map<String,TypeElement> serviceMap = new LinkedHashMap<String, TypeElement>();
  private Map<String,ArrayList<ExecutableElement>> methodMap = new LinkedHashMap<String, ArrayList<ExecutableElement>>();
  private boolean serviceInterfaceGenerated =false;
  private String mPackageName;
  private boolean isFirst =true;

  @Override public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    typeUtils = processingEnv.getTypeUtils();
    elementUtils = processingEnv.getElementUtils();
    filer = processingEnv.getFiler();
    messager = processingEnv.getMessager();
  }

  @Override public Set<String> getSupportedAnnotationTypes() {
    Set<String> annotataions = new LinkedHashSet<String>();
    annotataions.add(Factory.class.getCanonicalName());
    annotataions.add(Service.class.getCanonicalName());
    annotataions.add(Export.class.getCanonicalName());
    annotataions.add(ServiceInterface.class.getCanonicalName());
    annotataions.add(Proxy.class.getCanonicalName());
    return annotataions;
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  /**
   * Checks if the annotated element observes our rules
   */
  private void checkValidClass(FactoryAnnotatedClass item) throws ProcessingException {

    // Cast to TypeElement, has more type specific methods
    TypeElement classElement = item.getTypeElement();

    if (!classElement.getModifiers().contains(Modifier.PUBLIC)) {
      throw new ProcessingException(classElement, "The class %s is not public.",
          classElement.getQualifiedName().toString());
    }

    // Check if it's an abstract class
    if (classElement.getModifiers().contains(Modifier.ABSTRACT)) {
      throw new ProcessingException(classElement,
          "The class %s is abstract. You can't annotate abstract classes with @%",
          classElement.getQualifiedName().toString(), Factory.class.getSimpleName());
    }

    // Check inheritance: Class must be childclass as specified in @Factory.type();
    TypeElement superClassElement =
        elementUtils.getTypeElement(item.getQualifiedFactoryGroupName());
    if (superClassElement.getKind() == ElementKind.INTERFACE) {
      // Check interface implemented
      if (!classElement.getInterfaces().contains(superClassElement.asType())) {
        throw new ProcessingException(classElement,
            "The class %s annotated with @%s must implement the interface %s",
            classElement.getQualifiedName().toString(), Factory.class.getSimpleName(),
            item.getQualifiedFactoryGroupName());
      }
    } else {
      // Check subclassing
      TypeElement currentClass = classElement;
      while (true) {
        TypeMirror superClassType = currentClass.getSuperclass();

        if (superClassType.getKind() == TypeKind.NONE) {
          // Basis class (java.lang.Object) reached, so exit
          throw new ProcessingException(classElement,
              "The class %s annotated with @%s must inherit from %s",
              classElement.getQualifiedName().toString(), Factory.class.getSimpleName(),
              item.getQualifiedFactoryGroupName());
        }

        if (superClassType.toString().equals(item.getQualifiedFactoryGroupName())) {
          // Required super class found
          break;
        }

        // Moving up in inheritance tree
        currentClass = (TypeElement) typeUtils.asElement(superClassType);
      }
    }

    // Check if an empty public constructor is given
    for (Element enclosed : classElement.getEnclosedElements()) {
      if (enclosed.getKind() == ElementKind.CONSTRUCTOR) {
        ExecutableElement constructorElement = (ExecutableElement) enclosed;
        if (constructorElement.getParameters().size() == 0 && constructorElement.getModifiers()
            .contains(Modifier.PUBLIC)) {
          // Found an empty constructor
          return;
        }
      }
    }

    // No empty constructor found
    throw new ProcessingException(classElement,
        "The class %s must provide an public empty default constructor",
        classElement.getQualifiedName().toString());
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    try {

      // Scan classes
      for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(Factory.class)) {

        // Check if a class has been annotated with @Factory
        if (annotatedElement.getKind() != ElementKind.CLASS) {
          throw new ProcessingException(annotatedElement, "Only classes can be annotated with @%s",
              Factory.class.getSimpleName());
        }

        // We can cast it, because we know that it of ElementKind.CLASS
        TypeElement typeElement = (TypeElement) annotatedElement;

        FactoryAnnotatedClass annotatedClass = new FactoryAnnotatedClass(typeElement);

        checkValidClass(annotatedClass);

        // Everything is fine, so try to add
        FactoryGroupedClasses factoryClass =
            factoryClasses.get(annotatedClass.getQualifiedFactoryGroupName());
        if (factoryClass == null) {
          String qualifiedGroupName = annotatedClass.getQualifiedFactoryGroupName();
          factoryClass = new FactoryGroupedClasses(qualifiedGroupName);
          factoryClasses.put(qualifiedGroupName, factoryClass);
        }

        // Checks if id is conflicting with another @Factory annotated class with the same id
        factoryClass.add(annotatedClass);
      }

      for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(Service.class)) {

        // Check if a class has been annotated with @Factory
        if (annotatedElement.getKind() != ElementKind.CLASS) {
          throw new ProcessingException(annotatedElement, "Only classes can be annotated with @%s",
                  Service.class.getSimpleName());
        }

        if (isFirst) {
          PackageElement pkg = elementUtils.getPackageOf(annotatedElement);
          mPackageName = pkg.isUnnamed() ? null : pkg.getQualifiedName().toString();
          isFirst =false;
        }else {
          PackageElement pkg = elementUtils.getPackageOf(annotatedElement);
          String packageName = pkg.isUnnamed() ? null : pkg.getQualifiedName().toString();
          if (packageName != mPackageName){
            throw new ProcessingException(annotatedElement, "all classes  be annotated with @%s shoud in same package",
                    Service.class.getSimpleName());
          }
        }


        String name = annotatedElement.getAnnotation(Service.class).name();

        // We can cast it, because we know that it of ElementKind.CLASS
        TypeElement typeElement = (TypeElement) annotatedElement;

        serviceMap.put(name,typeElement);

        List<? extends Element> enclosedElements = typeElement.getEnclosedElements();
        List<ExecutableElement> executableElements = methodsIn(enclosedElements);
        if (enclosedElements.size() == 0){
          throw new ProcessingException(annotatedElement, "no methods be annotated with @%s",
                  Export.class.getSimpleName());
        }

        ArrayList<ExecutableElement> exportMethods = new ArrayList<>();

        for (ExecutableElement executableElement : executableElements) {
          if (executableElement.getAnnotation(Export.class) !=null) {
            if (executableElement.getKind() != ElementKind.METHOD){
              throw new ProcessingException(annotatedElement, "Only methods can be annotated with @%s",
                      Export.class.getSimpleName());
            }
            exportMethods.add(executableElement);
          }
        }
        if (exportMethods.size() ==0) {
          throw new ProcessingException(annotatedElement, "no methods be annotated with @%s",
                  Export.class.getSimpleName());
        }


        methodMap.put(name,exportMethods);
      }

      if (serviceMap.size() > 0 &&methodMap.size() > 0)
        generateSerivesInterface();


      for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ServiceInterface.class)) {

        // Check if a class has been annotated with @Factory
        if (annotatedElement.getKind() != ElementKind.INTERFACE) {
          throw new ProcessingException(annotatedElement, "Only interfaces can be annotated with @%s",
                  ServiceInterface.class.getSimpleName());
        }
        String name = annotatedElement.getAnnotation(ServiceInterface.class).name();
        // We can cast it, because we know that it of ElementKind.CLASS
        TypeElement typeElement = (TypeElement) annotatedElement;

        interfaceMap.put(name,typeElement);

      }

      if (interfaceMap.size() > 0) {
        generateSerivesProxy();
      }


      for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(Proxy.class)) {

        // Check if a class has been annotated with @Factory
        if (annotatedElement.getKind() != ElementKind.CLASS) {
          throw new ProcessingException(annotatedElement, "Only classes can be annotated with @%s",
                  Proxy.class.getSimpleName());
        }
        String name = annotatedElement.getAnnotation(Proxy.class).name();
        // We can cast it, because we know that it of ElementKind.CLASS
        TypeElement typeElement = (TypeElement) annotatedElement;

        proxyMap.put(name,typeElement);

      }
      if (proxyMap.size() > 0) {
        generateBundleActivator();

        serviceMap.clear();
        methodMap.clear();
        interfaceMap.clear();
        proxyMap.clear();
      }





      // Generate code
      for (FactoryGroupedClasses factoryClass : factoryClasses.values()) {
        factoryClass.generateCode(elementUtils, filer);
      }
      factoryClasses.clear();
    } catch (ProcessingException e) {
      error(e.getElement(), e.getMessage());
    } catch (IOException e) {
      error(null, e.getMessage());
    }

    return true;
  }

  private void generateBundleActivator() {
    ArrayList<FieldSpec> proxyFieldSpecList =new ArrayList<>();
    String regFieldName = "mServiceRegistration";
    String bundleContextParamName = "bundleContext";
    String body = "";
    for (Map.Entry<String,TypeElement> entry : proxyMap.entrySet()) {
      String name = entry.getKey();
      TypeElement proxyTypeElement = entry.getValue();
      TypeElement rawTypeElement = serviceMap.get(name);


      String proxyFieldName = "m" + name + PROXY_SUFFIX;
      FieldSpec proxyFeildSpec = FieldSpec.builder(TypeName.get(proxyTypeElement.asType()), proxyFieldName).build();
      proxyFieldSpecList.add(proxyFeildSpec);

      String createProxy = proxyFieldName + "= new " + proxyTypeElement.getSimpleName() + "(new " + rawTypeElement.getSimpleName() + "());\n";
      String reg = regFieldName + "=" + bundleContextParamName + "." + "registerService(" +"\""+ rawTypeElement.getQualifiedName().toString() + "\""+ "," + proxyFieldName + ",null);";
      body = body + createProxy;
      body = body + reg;
    }



    FieldSpec regFeildSpec = FieldSpec.builder(ServiceRegistration.class, regFieldName).build();


    MethodSpec startMethodSpec = MethodSpec.methodBuilder("start")
            .addModifiers(Modifier.PUBLIC)
            .addCode(body)
            .addParameter(BundleContext.class, bundleContextParamName)
            .returns(TypeName.VOID)
            .build();

    MethodSpec stopMethodSpec = MethodSpec.methodBuilder("stop")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(BundleContext.class, bundleContextParamName)
            .addStatement("$N.unregister()",regFeildSpec)
            .returns(TypeName.VOID)
            .build();

    TypeSpec bundleTypeSpec = TypeSpec.classBuilder(SIMPLE_BUNDLE)
            .addSuperinterface(BundleActivator.class)
            .addModifiers(Modifier.PUBLIC)
            .addField(regFeildSpec)
            .addFields(proxyFieldSpecList)
            .addMethod(startMethodSpec)
            .addMethod(stopMethodSpec)
            .build();

    try {
      JavaFile.builder(mPackageName, bundleTypeSpec).build().writeTo(filer);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  private void generateSerivesInterface() throws ProcessingException {


    for (Map.Entry<String,ArrayList<ExecutableElement>> entry : methodMap.entrySet()) {
      String name = entry.getKey();
      TypeElement typeElement = serviceMap.get(name);


      ArrayList<ExecutableElement> exportMethods = entry.getValue();

      ArrayList<MethodSpec> interMethodSpecList = new ArrayList<>();

      for (ExecutableElement exportMethod : exportMethods) {

        String methodName = exportMethod.getSimpleName().toString();

        List<? extends VariableElement> parameters = exportMethod.getParameters();


        ArrayList<ParameterSpec> paraSpecList = new ArrayList<>();

        for (int i = 0; i < parameters.size(); i++) {
          VariableElement parameter = parameters.get(i);

          String paraName = parameter.getSimpleName().toString();
          TypeMirror paraTypeMirror = parameter.asType();

          ParameterSpec parameterSpec = ParameterSpec.builder(TypeName.get(paraTypeMirror),paraName).build();
          paraSpecList.add(parameterSpec);
        }
//

        TypeMirror returnType = exportMethod.getReturnType();

        MethodSpec interMethodSpec = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT)
                .addParameters(paraSpecList)
                .returns(TypeName.get(returnType))
                .build();


        interMethodSpecList.add(interMethodSpec);

      }

      TypeSpec interTypeSpec = TypeSpec.interfaceBuilder(name)
              .addAnnotation(AnnotationSpec.builder(ServiceInterface.class)
                      .addMember("name", "$S", name)
                      .build())
              .addModifiers(Modifier.PUBLIC)
              .addMethods(interMethodSpecList)
              .build();

      // Write file
      try {
        JavaFile.builder(mPackageName, interTypeSpec).build().writeTo(filer);

      } catch (IOException e) {
        e.printStackTrace();
      }

      serviceInterfaceGenerated = true;

    }
  }


  private void generateSerivesProxy() throws ProcessingException {
    for (Map.Entry<String,ArrayList<ExecutableElement>> entry : methodMap.entrySet()) {
      String name = entry.getKey();
      TypeElement serviceTypeElement = serviceMap.get(name);
      TypeElement interfaceTypeElement = interfaceMap.get(name);

      ArrayList<ExecutableElement> exportMethods = entry.getValue();

      ArrayList<MethodSpec> proxyMethodSpecList = new ArrayList<>();

      for (ExecutableElement exportMethod : exportMethods) {

        String methodName = exportMethod.getSimpleName().toString();

        List<? extends VariableElement> parameters = exportMethod.getParameters();


        ArrayList<ParameterSpec> paraSpecList = new ArrayList<>();

        for (int i = 0; i < parameters.size(); i++) {
          VariableElement parameter = parameters.get(i);

          String paraName = parameter.getSimpleName().toString();
          TypeMirror paraTypeMirror = parameter.asType();

          ParameterSpec parameterSpec = ParameterSpec.builder(TypeName.get(paraTypeMirror),paraName).build();
          paraSpecList.add(parameterSpec);
        }
//

        TypeMirror returnType = exportMethod.getReturnType();
        String body = "return m" + name + "." + methodName +"(";

        if (paraSpecList.size() == 0) {
          body = "m" + name + "." + methodName +"()";
        }else {

          for (int i = 0; i < paraSpecList.size(); i++) {
            ParameterSpec parameterSpec = paraSpecList.get(i);
            body= body + parameterSpec.name;
            if (i < paraSpecList.size() -1) {
              body = body +",";
            }else {
              body = body +")";
            }
          }

        }



        MethodSpec proxyMethodSpec = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addStatement(body)
                .addParameters(paraSpecList)
                .returns(TypeName.get(returnType))
                .build();


        proxyMethodSpecList.add(proxyMethodSpec);
      }

      FieldSpec impFieldSpec = FieldSpec.builder(TypeName.get(serviceTypeElement.asType()),"m" + name).addModifiers(Modifier.PRIVATE).build();
      MethodSpec proxyCons = MethodSpec.constructorBuilder()
              .addModifiers(Modifier.PUBLIC)
              .addParameter(TypeName.get(serviceTypeElement.asType()),name.toLowerCase())
              .addStatement("this.$N = $N","m" + name,name.toLowerCase())
              .build();



      TypeSpec proxyTypeSpec = TypeSpec.classBuilder(name + PROXY_SUFFIX)
              .addSuperinterface(TypeName.get(interfaceTypeElement.asType()))
              .addAnnotation(AnnotationSpec.builder(Proxy.class)
                      .addMember("name", "$S", name)
                      .build())
              .addMethods(proxyMethodSpecList)
              .addModifiers(Modifier.PUBLIC)
              .addField(impFieldSpec)
              .addMethod(proxyCons)
              .build();

      // Write file
      try {
        JavaFile.builder(mPackageName, proxyTypeSpec).build().writeTo(filer);
      } catch (IOException e) {
        e.printStackTrace();
      }

    }
  }
  /**
   * Prints an error message
   *
   * @param e The element which has caused the error. Can be null
   * @param msg The error message
   */
  public void error(Element e, String msg) {
    messager.printMessage(Diagnostic.Kind.ERROR, msg, e);
  }


}
