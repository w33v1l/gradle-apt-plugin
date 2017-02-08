package net.ltgt.gradle.apt

import groovy.transform.PackageScope
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.GroovyBasePlugin
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.scala.ScalaBasePlugin
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin

class AptPlugin implements Plugin<Project> {
  @Override
  void apply(Project project) {
    def cl = { AbstractCompile task ->
      task.convention.plugins.put("net.ltgt.apt", new AptConvention(project))
      task.inputs.property("aptOptions.annotationProcessing", { task.convention.getPlugin(AptConvention).aptOptions.annotationProcessing })
      task.inputs.property("aptOptions.processors", { task.convention.getPlugin(AptConvention).aptOptions.processors })
      task.inputs.property("aptOptions.processorArgs", { task.convention.getPlugin(AptConvention).aptOptions.processorArgs })
      def propBuilder = task.inputs.files { task.convention.getPlugin(AptConvention).aptOptions.processorpath }
      if (!propBuilder.is(task.inputs)) {
        propBuilder.withPropertyName("aptOptions.processorpath")
      }
      propBuilder = task.outputs.dir { task.convention.getPlugin(AptConvention).generatedSourcesDestinationDir }
      if (!propBuilder.is(task.outputs)) {
        propBuilder.withPropertyName("generatedSourcesDestinationDir")
      }
      task.doFirst {
        def aptConvention = task.convention.getPlugin(AptConvention)
        aptConvention.makeDirectories()
        task.options.compilerArgs += aptConvention.buildCompilerArgs()
      }
    }
    project.tasks.withType(JavaCompile, cl)
    project.tasks.withType(GroovyCompile, cl)
    project.tasks.withType(ScalaCompile, cl)

    project.plugins.withType(JavaBasePlugin) {
      def javaConvention = project.convention.getPlugin(JavaPluginConvention)
      javaConvention.sourceSets.all { SourceSet sourceSet ->
        def convention = new AptSourceSetConvention(project, sourceSet)
        sourceSet.convention.plugins.put("net.ltgt.apt", convention)

        sourceSet.output.convention.plugins.put("net.ltgt.apt", new AptSourceSetOutputConvention(project, sourceSet))

        def compileOnlyConfigurationName = convention.compileOnlyConfigurationName
        // Gradle 2.12 already creates such a configuration in the JavaBasePlugin; our compileOnlyConfigurationName has the same value
        def configuration = project.configurations.findByName(compileOnlyConfigurationName)
        if (configuration == null) {
          configuration = project.configurations.create(compileOnlyConfigurationName)
          configuration.visible = false
          configuration.description = "Compile-only classpath for ${sourceSet}."
          configuration.extendsFrom project.configurations.findByName(sourceSet.compileConfigurationName)

          sourceSet.compileClasspath = configuration

          // Special-case the JavaPlugin's 'test' source set, only if we created the testCompileOnly configuration
          // Note that Gradle 2.12 actually creates a testCompilationClasspath configuration that extends testCompileOnly
          // and sets it as sourceSets.test.compileClasspath; rather than directly using the testCompileOnly configuration.
          if (sourceSet.name == SourceSet.TEST_SOURCE_SET_NAME) {
            project.plugins.withType(JavaPlugin) {
              sourceSet.compileClasspath = project.files(javaConvention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).output, configuration)
            }
          }
        }

        def aptConfiguration = project.configurations.create(convention.aptConfigurationName)
        aptConfiguration.visible = false
        aptConfiguration.description = "Processor path for ${sourceSet}"

        configureCompileTask(project, sourceSet, sourceSet.compileJavaTaskName)
      }
    }
    project.plugins.withType(JavaPlugin) {
      def javaConvention = project.convention.getPlugin(JavaPluginConvention)
      def mainSourceSet = javaConvention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
      def testSourceSet = javaConvention.sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)

      configureEclipse(project, mainSourceSet, testSourceSet)

      configureIdeaModule(project, mainSourceSet, testSourceSet)
    }
    project.plugins.withType(GroovyBasePlugin) {
      def javaConvention = project.convention.getPlugin(JavaPluginConvention)
      javaConvention.sourceSets.all { SourceSet sourceSet ->
        configureCompileTask(project, sourceSet, sourceSet.getCompileTaskName("groovy"))
      }
    }
    project.plugins.withType(ScalaBasePlugin) {
      def javaConvention = project.convention.getPlugin(JavaPluginConvention)
      javaConvention.sourceSets.all { SourceSet sourceSet ->
        configureCompileTask(project, sourceSet, sourceSet.getCompileTaskName("scala"))
      }
    }
    configureIdeaProject(project)
  }

  private void configureCompileTask(Project project, SourceSet sourceSet, String taskName) {
    def task = project.tasks.withType(AbstractCompile).getByName(taskName)
    def aptConvention = task.convention.getPlugin(AptConvention)
    aptConvention.generatedSourcesDestinationDir = { sourceSet.output.convention.getPlugin(AptSourceSetOutputConvention).generatedSourcesDir }
    aptConvention.aptOptions.processorpath = { sourceSet.convention.getPlugin(AptSourceSetConvention).processorpath }
  }

  /**
   * Inspired by https://github.com/mkarneim/pojobuilder/wiki/Enabling-PojoBuilder-for-Eclipse-Using-Gradle
   */
  private void configureEclipse(Project project, SourceSet mainSourceSet, SourceSet testSourceSet) {
    project.plugins.withType(EclipsePlugin) {
      project.eclipse.jdt.file.withProperties {
        it.'org.eclipse.jdt.core.compiler.processAnnotations' = 'enabled'
      }

      project.afterEvaluate {
        project.eclipse.classpath {
          plusConfigurations += [
              project.configurations.getByName(mainSourceSet.compileOnlyConfigurationName),
              project.configurations.getByName(testSourceSet.compileOnlyConfigurationName)
          ]
        }
      }
      if (!project.tasks.findByName('eclipseJdtApt')) {
        def task = project.tasks.create('eclipseJdtApt') {
          ext.aptPrefs = project.file('.settings/org.eclipse.jdt.apt.core.prefs')
          outputs.file(aptPrefs)
          doLast {
            project.mkdir(aptPrefs.parentFile)
            aptPrefs.text = """\
              eclipse.preferences.version=1
              org.eclipse.jdt.apt.aptEnabled=true
              org.eclipse.jdt.apt.genSrcDir=.apt_generated
              org.eclipse.jdt.apt.reconcileEnabled=true
            """.stripIndent()
          }
        }
        project.tasks.eclipse.dependsOn task
        def cleanTask = project.tasks.create('cleanEclipseJdtApt', Delete)
        cleanTask.delete task.outputs
        project.tasks.cleanEclipse.dependsOn cleanTask
      }
      if (!project.tasks.findByName('eclipseFactorypath')) {
        def task = project.tasks.create('eclipseFactorypath') {
          ext.factorypath = project.file('.factorypath')
          inputs.files project.configurations.getByName(mainSourceSet.aptConfigurationName),
              project.configurations.getByName(testSourceSet.aptConfigurationName)
          outputs.file factorypath
          doLast {
            factorypath.withWriter {
              new groovy.xml.MarkupBuilder(it).'factorypath' {
                [project.configurations.getByName(mainSourceSet.aptConfigurationName),
                 project.configurations.getByName(testSourceSet.aptConfigurationName)]*.each {
                  factorypathentry(
                      kind: 'EXTJAR',
                      id: it.absolutePath,
                      enabled: true,
                      runInBatchMode: false,
                  )
                }
              }
            }
          }
        }
        project.tasks.eclipse.dependsOn task
        def cleanTask = project.tasks.create('cleanEclipseFactorypath', Delete)
        cleanTask.delete task.outputs
        project.tasks.cleanEclipse.dependsOn cleanTask
      }
    }
  }

  private void configureIdeaModule(Project project, SourceSet mainSourceSet, SourceSet testSourceSet) {
    project.plugins.withType(IdeaPlugin) {
      project.afterEvaluate {
        project.idea.module {
          def excl = [ mainSourceSet, testSourceSet ].collect { it.output.generatedSourcesDir }
              .collect {
                def ancestors = []
                for (File f = it; f != null && f != project.projectDir; f = f.parentFile) {
                  ancestors.add(f)
                }
                return ancestors
              }
              .flatten()

          if (excl.contains(project.buildDir) && excludeDirs.contains(project.buildDir)) {
            excludeDirs -= project.buildDir
            // Race condition: many of these will actually be created afterwards…
            def subdirs = project.buildDir.listFiles({ f -> f.directory } as FileFilter)
            if (subdirs != null) {
              excludeDirs += subdirs as List
            }
          }
          excludeDirs -= excl

          sourceDirs += mainSourceSet.output.generatedSourcesDir
          testSourceDirs += testSourceSet.output.generatedSourcesDir
          generatedSourceDirs += [ mainSourceSet.output.generatedSourcesDir, testSourceSet.output.generatedSourcesDir ]

          // NOTE: ideally we'd use PROVIDED for both, but then every transitive dependency in
          // compile or testCompile configurations that would also be in compileOnly and
          // testCompileOnly would end up in PROVIDED.
          scopes.COMPILE.plus += [
              project.configurations.getByName(mainSourceSet.compileOnlyConfigurationName),
              project.configurations.getByName(mainSourceSet.aptConfigurationName)
          ]
          scopes.TEST.plus += [
              project.configurations.getByName(testSourceSet.compileOnlyConfigurationName),
              project.configurations.getByName(testSourceSet.aptConfigurationName)
          ]
        }
      }
    }
  }

  private void configureIdeaProject(Project project) {
    if (project.parent == null) {
      project.plugins.withType(IdeaPlugin) {
        project.idea.project.ipr.withXml {
          def compilerConfiguration = it.node.component.find { it.@name == 'CompilerConfiguration' }
          compilerConfiguration.remove(compilerConfiguration.annotationProcessing)
          compilerConfiguration.append(new NodeBuilder().annotationProcessing() {
            profile(name: 'Default', enabled: true, default: true) {
              // XXX: this assumes that all subprojects use the same name for their buildDir
              sourceOutputDir(name: "${project.relativePath(project.buildDir)}/generated/source/apt/$SourceSet.MAIN_SOURCE_SET_NAME")
              sourceTestOutputDir(name: "${project.relativePath(project.buildDir)}/generated/source/apt/$SourceSet.TEST_SOURCE_SET_NAME")
              outputRelativeToContentRoot(value: true)
              processorPath(useClasspath: true)
            }
          })
        }
      }
    }
  }

  class AptConvention {
    private final Project project

    AptConvention(Project project) {
      this.project = project
      this.aptOptions = new AptOptions(project);
    }

    private Object generatedSourcesDestinationDir

    public File getGeneratedSourcesDestinationDir() {
      if (generatedSourcesDestinationDir == null) {
        return null
      }
      return project.file(generatedSourcesDestinationDir)
    }

    public void setGeneratedSourcesDestinationDir(Object generatedSourcesDestinationDir) {
      this.generatedSourcesDestinationDir = generatedSourcesDestinationDir
    }

    final AptOptions aptOptions

    @PackageScope void makeDirectories() {
      if (generatedSourcesDestinationDir != null) {
        project.mkdir(generatedSourcesDestinationDir)
      }
    }

    @PackageScope List<String> buildCompilerArgs() {
      def result = []
      if (generatedSourcesDestinationDir != null) {
        result += ["-s", getGeneratedSourcesDestinationDir().path]
      }
      if (!aptOptions.annotationProcessing) {
        result += ["-proc:none"]
      }
      if (aptOptions.processorpath != null && !aptOptions.processorpath.empty) {
        result += ["-processorpath", aptOptions.processorpath.asPath]
      }
      if (aptOptions.processors != null && !aptOptions.processors.empty) {
        result += ["-processor", aptOptions.processors.join(",")]
      }
      result += aptOptions.processorArgs?.collect { key, value -> "-A$key=$value" }
      return result
    }
  }

  class AptOptions {
    private final Project project

    AptOptions(Project project) {
      this.project = project
    }

    boolean annotationProcessing = true

    private Object processorpath

    public FileCollection getProcessorpath() {
      if (processorpath == null) {
        return null
      }
      return project.files(processorpath)
    }

    public void setProcessorpath(Object processorpath) {
      this.processorpath = processorpath
    }

    List<?> processors = []
    Map<String, ?> processorArgs = [:]
  }

  class AptSourceSetConvention {
    private final Project project
    private final SourceSet sourceSet

    AptSourceSetConvention(Project project, SourceSet sourceSet) {
      this.project = project
      this.sourceSet = sourceSet
      this.processorpath = { project.configurations.findByName(this.aptConfigurationName) }
    }

    private Object processorpath

    public FileCollection getProcessorpath() {
      if (processorpath == null) {
        return null;
      }
      return project.files(processorpath)
    }

    public void setProcessorpath(Object processorpath) {
      this.processorpath = processorpath
    }

    public String getCompileOnlyConfigurationName() {
      return sourceSet.compileConfigurationName + "Only"
    }

    public String getAptConfigurationName() {
      // HACK: we use the same naming logic/scheme as for tasks, so just use SourceSet#getTaskName
      return sourceSet.getTaskName("", "apt")
    }
  }

  class AptSourceSetOutputConvention {
    private final Project project
    private final SourceSet sourceSet

    AptSourceSetOutputConvention(Project project, SourceSet sourceSet) {
      this.project = project
      this.sourceSet = sourceSet
      this.generatedSourcesDir = { project.file("${project.buildDir}/generated/source/apt/${sourceSet.name}") }
    }

    private Object generatedSourcesDir

    public File getGeneratedSourcesDir() {
      if (generatedSourcesDir == null) {
        return null
      }
      return project.file(generatedSourcesDir)
    }

    public void setGeneratedSourcesDir(Object generatedSourcesDir) {
      this.generatedSourcesDir = generatedSourcesDir
    }
  }
}
