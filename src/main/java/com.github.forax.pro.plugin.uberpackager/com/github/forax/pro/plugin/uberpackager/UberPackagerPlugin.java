package com.github.forax.pro.plugin.uberpackager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.Module;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import com.github.forax.pro.api.Config;
import com.github.forax.pro.api.MutableConfig;
import com.github.forax.pro.api.Plugin;
import com.github.forax.pro.api.WatcherRegistry;
import com.github.forax.pro.api.helper.CmdLine;
import com.github.forax.pro.api.helper.ProConf;
import com.github.forax.pro.helper.FileHelper;
import com.github.forax.pro.helper.Log;
import com.github.forax.pro.helper.util.StableList;
import com.github.forax.pro.ubermain.Main;

public class UberPackagerPlugin implements Plugin {
  @Override
  public String name() {
    return "uberpackager";
  }

  @Override
  public void init(MutableConfig config) {
    config.getOrUpdate(name(), UberPackagerConf.class);
  }
  
  @Override
  public void configure(MutableConfig config) {
    UberPackagerConf packager = config.getOrUpdate(name(), UberPackagerConf.class);
    ConventionFacade convention = config.getOrThrow("convention", ConventionFacade.class); 
    
    // inputs
    packager.moduleArtifactSourcePath(convention.javaModuleArtifactSourcePath());
    packager.moduleDependencyPath(convention.javaModuleDependencyPath());
    
    // outputs
    packager.moduleUberPath(convention.javaModuleUberPath());
    packager.moduleUberExplodedPath(convention.javaModuleUberExplodedPath());
  }
  
  @Override
  public void watch(Config config, WatcherRegistry registry) {
    UberPackagerConf packager = config.getOrThrow(name(), UberPackagerConf.class);
    packager.moduleArtifactSourcePath().forEach(registry::watch);
    packager.moduleDependencyPath().forEach(registry::watch);
  }
  
  @Override
  public int execute(Config config) throws IOException {
    Log log = Log.create(name(), config.getOrThrow("pro", ProConf.class).loglevel());
    log.debug(config, conf -> "config " + config);
    
    ToolProvider jarTool = ToolProvider.findFirst("jar")
        .orElseThrow(() -> new IllegalStateException("can not find the command jar"));
    UberPackagerConf packager = config.getOrThrow(name(), UberPackagerConf.class);
    
    Path uberExplodedPath = packager.moduleUberExplodedPath();
    FileHelper.deleteAllFiles(uberExplodedPath, false);
    Files.createDirectories(uberExplodedPath);
    
    Class<Main> mainClass = com.github.forax.pro.ubermain.Main.class;
    Module uberjarModule = mainClass.getModule();
    ModuleReference uberJarRef = uberjarModule.getLayer().configuration().findModule(uberjarModule.getName()).get().reference();
    Path uberJarModulePath;
    try(ModuleReader moduleReader = uberJarRef.open()) {
      String mainClassName = mainClass.getName().replace('.', '/');
      for(String filename: List.of(mainClassName + ".class", mainClassName + "$1.class")) {
        Path path = uberExplodedPath.resolve(filename);
        Files.createDirectories(path.getParent());
        Files.copy(moduleReader.open(filename).get(), path);
      }
      
      Path uberJarPath = Paths.get(uberJarRef.location().get());
      uberJarModulePath = uberJarPath.getFileName();
      Files.copy(uberJarPath, uberExplodedPath.resolve(uberJarModulePath));
    }
    
    List<Path> modulePaths = new StableList<Path>()
        .append(packager.moduleArtifactSourcePath())
        .appendAll(packager.moduleDependencyPath());
    
    try(BufferedWriter writer = Files.newBufferedWriter(uberExplodedPath.resolve("modules.txt"))) {
      writer.write("com.github.forax.pro.main/com.github.forax.pro.main.Main");
      writer.newLine();
      writer.write(uberJarModulePath.toString());
      writer.newLine();
      for(Path modulePath: modulePaths) {
        try(Stream<Path> modularJars = Files.list(modulePath)) {
          for(Path modularJar: (Iterable<Path>)modularJars::iterator){
            //System.out.println("modularJar " + modularJar);
            writer.write(modularJar.getFileName().toString());
            writer.newLine();
          }
        }
      }
    }
    
    Path uberjar = packager.moduleUberPath().resolve("uber.jar");
    CmdLine cmdLine = new CmdLine().addAll(
        "--create",
        "--file", uberjar.toString(),
        "--main-class", mainClass.getName(),
        "-C", uberExplodedPath.toString(),
        "."
        );
    String[] arguments = cmdLine.toArguments();
    log.verbose(arguments, args -> "jar " + String.join(" ", args));
    jarTool.run(System.out, System.err, arguments);
    
    for(Path modulePath: modulePaths) {
      cmdLine = new CmdLine().addAll(
          "--update",
          "--file", uberjar.toString(),
          "-C", modulePath.toString(),
          "."
          );
      arguments = cmdLine.toArguments();
      log.verbose(arguments, args -> "jar " + String.join(" ", args));
      jarTool.run(System.out, System.err, arguments);
    }
    
    return 0;
  }
}
