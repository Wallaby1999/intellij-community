/*
 * Copyright 2000-2007 JetBrains s.r.o.
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
package com.intellij.execution.configurations;

import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectClasspathTraversing;
import com.intellij.openapi.roots.ProjectRootsTraversing;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.util.PathsList;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class JavaParameters {
  public static final DataKey<JavaParameters> JAVA_PARAMETERS = DataKey.create("javaParameters");

  private Sdk myJdk;
  private final PathsList myClassPath = new PathsList();
  private String myMainClass;
  private final ParametersList myVmParameters = new ParametersList();
  private final ParametersList myProgramParameters = new ParametersList();
  private String myWorkingDirectory;
  private Charset myCharset = CharsetToolkit.getDefaultSystemCharset();
  private Map<String, String> myEnv;
  private boolean myPassParentEnvs = true;

  public String getWorkingDirectory() {
    return myWorkingDirectory;
  }

  public String getMainClass() {
    return myMainClass;
  }

  /**
   * @return jdk used to launch the application.
   * If the instance of the JavaParameters is used to configure app server startup script, 
   * then null is returned.
   */
  @Nullable
  public Sdk getJdk() {
    return myJdk;
  }

  public String getJdkPath() throws CantRunException {
    final Sdk jdk = getJdk();
    if(jdk == null) {
      throw new CantRunException(ExecutionBundle.message("no.jdk.specified..error.message"));
    }

    final String jdkHome = jdk.getHomeDirectory().getPresentableUrl();
    if(jdkHome == null || jdkHome.length() == 0) {
      throw new CantRunException(ExecutionBundle.message("home.directory.not.specified.for.jdk.error.message"));
    }
    return jdkHome;
  }

  public void setJdk(final Sdk jdk) {
    myJdk = jdk;
  }

  public void setMainClass(@NonNls final String mainClass) {
    myMainClass = mainClass;
  }

  public void setWorkingDirectory(final File path) {
    setWorkingDirectory(path.getPath());
  }

  public void setWorkingDirectory(@NonNls final String path) {
    myWorkingDirectory = path;
  }

  public static final int JDK_ONLY = 0x1;
  public static final int CLASSES_ONLY = 0x2;
  private static final int TESTS_ONLY = 0x4;
  public static final int JDK_AND_CLASSES = JDK_ONLY | CLASSES_ONLY;
  public static final int JDK_AND_CLASSES_AND_TESTS = JDK_ONLY | CLASSES_ONLY | TESTS_ONLY;

  public void configureByModule(final Module module, final int classPathType, final Sdk jdk) throws CantRunException {
    if ((classPathType & JDK_ONLY) != 0) {
      if (jdk == null) {
        throw CantRunException.noJdkConfigured();
      }
      myJdk = jdk;
    }

    if((classPathType & CLASSES_ONLY) == 0) {
      return;
    }

    Charset encoding = EncodingProjectManager.getInstance(module.getProject()).getDefaultCharset();
    if (encoding != null) {
      myCharset = encoding;
    }
    ProjectRootsTraversing.collectRoots(module, (classPathType & TESTS_ONLY) != 0 ? ProjectClasspathTraversing.FULL_CLASSPATH_RECURSIVE : ProjectClasspathTraversing.FULL_CLASSPATH_WITHOUT_TESTS, myClassPath);
  }

  public void configureByModule(final Module module, final int classPathType) throws CantRunException {
    configureByModule(module, classPathType, getModuleJdk(module));
  }

  public static Sdk getModuleJdk(final Module module) throws CantRunException {
    final Sdk jdk = ModuleRootManager.getInstance(module).getSdk();
    if (jdk == null) {
      throw CantRunException.noJdkForModule(module);
    }
    final VirtualFile homeDirectory = jdk.getHomeDirectory();
    if (homeDirectory == null || !homeDirectory.isValid()) {
      throw CantRunException.jdkMisconfigured(jdk, module);
    }
    return jdk;
  }

  public void configureByProject(final Project project, final int classPathType, final Sdk jdk ) throws CantRunException {
    if ((classPathType & JDK_ONLY) != 0) {
      if (jdk == null) {
        throw CantRunException.noJdkConfigured();
      }
      myJdk = jdk;
    }

    if ((classPathType & CLASSES_ONLY) == 0) {
      return;
    }

    ProjectRootsTraversing.collectRoots(project, (classPathType & TESTS_ONLY) != 0 ? ProjectClasspathTraversing.FULL_CLASSPATH_RECURSIVE : ProjectClasspathTraversing.FULL_CLASSPATH_WITHOUT_TESTS, myClassPath);
  }

  public ParametersList getVMParametersList() {
    return myVmParameters;
  }

  public ParametersList getProgramParametersList() {
    return myProgramParameters;
  }

  public Charset getCharset() {
    return myCharset;
  }

  public void setCharset(final Charset charset) {
    myCharset = charset;
  }

  public PathsList getClassPath() {
    return myClassPath;
  }

  public Map<String, String> getEnv() {
    return myEnv;
  }

  public void setEnv(final Map<String, String> env) {
    myEnv = env;
  }

  public boolean isPassParentEnvs() {
    return myPassParentEnvs;
  }

  public void setPassParentEnvs(final boolean passDefaultEnvs) {
    myPassParentEnvs = passDefaultEnvs;
  }

  public void setupEnvs(Map<String, String> envs, boolean passDefault) {
    if (!envs.isEmpty()) {
      final HashMap<String, String> map = new HashMap<String, String>(envs);
      EnvironmentVariablesComponent.inlineParentOccurrences(map);
      setEnv(map);
      setPassParentEnvs(passDefault);
    }
  }
}