/*
 * JBoss, by Red Hat.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.forge.shell.plugins.builtin;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.enterprise.event.Event;
import javax.inject.Inject;

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.jboss.forge.ForgeEnvironment;
import org.jboss.forge.git.GitUtils;
import org.jboss.forge.parser.xml.Node;
import org.jboss.forge.parser.xml.XMLParser;
import org.jboss.forge.project.Project;
import org.jboss.forge.project.dependencies.Dependency;
import org.jboss.forge.project.dependencies.DependencyBuilder;
import org.jboss.forge.project.dependencies.DependencyRepository;
import org.jboss.forge.project.dependencies.DependencyRepositoryImpl;
import org.jboss.forge.project.dependencies.DependencyResolver;
import org.jboss.forge.project.dependencies.ScopeType;
import org.jboss.forge.project.facets.DependencyFacet;
import org.jboss.forge.project.facets.DependencyFacet.KnownRepository;
import org.jboss.forge.project.facets.MetadataFacet;
import org.jboss.forge.project.facets.PackagingFacet;
import org.jboss.forge.project.packaging.PackagingType;
import org.jboss.forge.resources.DependencyResource;
import org.jboss.forge.resources.DirectoryResource;
import org.jboss.forge.resources.FileResource;
import org.jboss.forge.resources.Resource;
import org.jboss.forge.shell.InstalledPluginRegistry;
import org.jboss.forge.shell.InstalledPluginRegistry.PluginEntry;
import org.jboss.forge.shell.Shell;
import org.jboss.forge.shell.ShellColor;
import org.jboss.forge.shell.ShellMessages;
import org.jboss.forge.shell.ShellPrintWriter;
import org.jboss.forge.shell.ShellPrompt;
import org.jboss.forge.shell.events.ReinitializeEnvironment;
import org.jboss.forge.shell.exceptions.Abort;
import org.jboss.forge.shell.plugins.Alias;
import org.jboss.forge.shell.plugins.Command;
import org.jboss.forge.shell.plugins.DefaultCommand;
import org.jboss.forge.shell.plugins.Help;
import org.jboss.forge.shell.plugins.Option;
import org.jboss.forge.shell.plugins.PipeOut;
import org.jboss.forge.shell.plugins.Plugin;
import org.jboss.forge.shell.plugins.Topic;
import org.jboss.forge.shell.util.PluginRef;
import org.jboss.forge.shell.util.PluginUtil;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
@Alias("forge")
@Topic("Shell Environment")
@Help("Forge control and writer environment commands. Manage plugins and other forge addons.")
public class ForgePlugin implements Plugin
{
   private static final String MODULE_TEMPLATE_XML = "/org/jboss/forge/modules/module-template.xml";
   private final Event<ReinitializeEnvironment> reinitializeEvent;
   private final ShellPrintWriter writer;
   private final DependencyResolver resolver;
   private final ForgeEnvironment environment;
   private final ShellPrompt prompt;
   private final Shell shell;

   @Inject
   public ForgePlugin(final ForgeEnvironment environment, final Event<ReinitializeEnvironment> reinitializeEvent,
            final ShellPrintWriter writer, final ShellPrompt prompt, final DependencyResolver resolver,
            final Shell shell)
   {
      this.environment = environment;
      this.reinitializeEvent = reinitializeEvent;
      this.writer = writer;
      this.prompt = prompt;
      this.shell = shell;
      this.resolver = resolver;
   }

   /*
    * General methods
    */

   @DefaultCommand
   public void about(final PipeOut out)
   {
      out.println("    _____                    ");
      out.println("   |  ___|__  _ __ __ _  ___ ");
      out.println("   | |_ / _ \\| `__/ _` |/ _ \\  "
               + out.renderColor(ShellColor.YELLOW, "\\\\"));
      out.println("   |  _| (_) | | | (_| |  __/  "
               + out.renderColor(ShellColor.YELLOW, "//"));
      out.println("   |_|  \\___/|_|  \\__, |\\___| ");
      out.println("                   |___/      ");
      out.println("");
      out.println("JBoss Forge, version [ " + environment.getRuntimeVersion()
               + " ] - JBoss, by Red Hat, Inc. [ http://jboss.org/forge ]");
   }

   @Command(value = "restart", help = "Reload all plugins and default configurations")
   public void restart() throws Exception
   {
      reinitializeEvent.fire(new ReinitializeEnvironment());
   }

   @Command(value = "list-plugins", help = "List all installed plugin JAR files.")
   public void listInstalled(
            @Option(name = "all",
                     shortName = "a",
                     description = "Show extra information about each installed plugin",
                     defaultValue = "false") final boolean showAll)
   {
      DirectoryResource pluginDir = environment.getPluginDirectory();

      displayModules(pluginDir);
   }

   private void displayModules(final DirectoryResource pluginDir)
   {
      List<PluginEntry> plugins = InstalledPluginRegistry.list();
      for (PluginEntry plugin : plugins) {
         writer.println(plugin.toString());
      }
   }

   /*
    * Plugin installation
    */

   @Command(value = "find-plugin",
            help = "Searches the configured Forge plugin index for a plugin matching the given search text")
   public void find(@Option(description = "search string") final String searchString, final PipeOut out)
            throws Exception
   {
      List<PluginRef> pluginList = PluginUtil.findPlugin(environment, searchString, out);

      if (!pluginList.isEmpty())
      {
         out.println();
      }
      for (PluginRef ref : pluginList)
      {
         out.println(" - " + out.renderColor(ShellColor.BOLD, ref.getName()) + " (" + ref.getArtifact() + ")");
         out.println("\tAuthor: " + ref.getAuthor());
         out.println("\tWebsite: " + ref.getWebsite());
         out.println("\tLocation: " + ref.getLocation());
         out.println("\tTags: " + ref.getTags());
         out.println("\tDescription: " + ref.getDescription());
         out.println();
      }
   }

   @Command(value = "remove-plugin",
            help = "Removes a plugin from the current Forge runtime configuration")
   public void removePlugin(
            @Option(completer = InstalledPluginCompleter.class, description = "plugin-name", required = true,
                     help = "The fully qualified plugin name e.g: 'org.jboss.forge.plugin:version'") final String pluginName,
            final PipeOut out) throws Exception
   {

      PluginEntry plugin = PluginEntry.fromCoordinates(pluginName);
      if (!InstalledPluginRegistry.has(plugin))
      {
         throw new RuntimeException("No such installed plugin [" + pluginName + "]");
      }
      InstalledPluginRegistry.remove(InstalledPluginRegistry.get(plugin));

      if (!InstalledPluginRegistry.has(plugin))
      {
         ShellMessages.success(out, "Successfully removed [" + pluginName + "]");
         restart();
      }
      else
      {
         ShellMessages.error(out, "Failed to remove [" + pluginName + "");
      }
   }

   @Command(value = "install-plugin",
            help = "Installs a plugin from the configured Forge plugin index")
   public void installFromIndex(@Option(description = "plugin-name") final String pluginName,
            final PipeOut out) throws Exception
   {
      List<PluginRef> plugins = PluginUtil.findPlugin(environment, pluginName, out);

      if (plugins.isEmpty())
      {
         throw new RuntimeException("no plugin found with name [" + pluginName + "]");
      }
      else if (plugins.size() > 1)
      {
         throw new RuntimeException("ambiguous plugin query: multiple matches for [" + pluginName + "]");
      }
      else
      {
         PluginRef ref = plugins.get(0);
         ShellMessages.info(out, "Preparing to install plugin: " + ref.getName());

         if (!ref.isGit())
         {
            installFromMvnRepos(ref.getArtifact(), out, new DependencyRepositoryImpl("custom", ref.getHomeRepo()));
         }
         else if (ref.isGit())
         {
            installFromGit(ref.getGitRepo(), ref.getGitRef(), null, out);
         }
      }
   }

   private void installFromMvnRepos(final Dependency dep, final PipeOut out, final DependencyRepository... repoList)
            throws Exception
   {
      installFromMvnRepos(dep, out, Arrays.asList(repoList));
   }

   private void installFromMvnRepos(final Dependency dep, final PipeOut out, final List<DependencyRepository> repoList)
            throws Exception
   {
      List<DependencyResource> temp = resolver.resolveArtifacts(dep, repoList);
      List<DependencyResource> artifacts = new ArrayList<DependencyResource>();

      for (DependencyResource d : temp)
      {
         if (d.exists())
         {
            artifacts.add(d);
         }
      }

      if (artifacts.isEmpty())
      {
         throw new RuntimeException("No artifacts found for [" + dep + "]");
      }
      else if (artifacts.size() > 1)
      {
         prompt.promptChoiceTyped("Install which version?", artifacts, artifacts.get(artifacts.size() - 1));
      }
      else
      {
         artifacts.get(0);
      }

      // TODO Build module from maven artifact
      // createModuleFromMavenArtifact(artifact);
      // ShellMessages.success(out, "Installed from [" + dep.toCoordinates() + "] successfully.");

      throw new IllegalStateException("Not yet implemented.");
   }

   // @Command(value = "mvn-plugin",
   // help = "Download and install a plugin from a maven repository")
   public void installFromMvnRepos(@Option(description = "plugin-identifier", required = true) final Dependency dep,
            @Option(name = "knownRepo", description = "target repository") final KnownRepository repo,
            @Option(name = "repoUrl", description = "target repository URL") final String repoURL,
            final PipeOut out) throws Exception
   {
      throw new IllegalStateException("Not implemented");
      /*
      if (repoURL != null)
      {
         installFromMvnRepos(dep, out,
                  new DependencyRepositoryImpl("custom", repoURL));
      }
      else if (repo == null)
      {
         List<DependencyRepository> repos = new ArrayList<DependencyRepository>();
         for (KnownRepository r : KnownRepository.values())
         {
            repos.add(new DependencyRepositoryImpl(r));
         }
         installFromMvnRepos(dep, out, repos);
      }
      else
         installFromMvnRepos(dep, out, new DependencyRepositoryImpl(repo));
         */
   }

   // @Command(value = "jar-plugin",
   // help = "Install a plugin from a local project folder")
   public void installFromLocalJar(
            @Option(name = "jar", description = "jar file to install", required = true) final Resource<?> resource,
            @Option(name = "id", description = "plugin identifier, [e.g. \"com.example.group : example-plugin\"]", required = true) final Dependency dep,
            final PipeOut out) throws Exception
   {

      throw new IllegalStateException("Not implemented");
      /*
      FileResource<?> source = resource.reify(FileResource.class);
      if ((source == null) || !source.exists())
      {
         throw new IllegalArgumentException("JAR file must be specified.");
      }

      if (environment.getPluginDirectory().equals(source.getParent()))
      {
         throw new IllegalArgumentException("Plugin is already installed.");
      }

      ShellMessages.info(out, "WARNING!");
      if (prompt.promptBoolean(
               "Installing plugins from remote sources is dangerous, and can leave untracked plugins. Continue?", true))
      {
         FileResource<?> target = createIncrementedPluginJarFile(dep);
         target.setContents(source.getResourceInputStream());

         ShellMessages.success(out, "Installed from [" + resource + "] successfully.");
         restart();
      }
      else
         throw new RuntimeException("Aborted.");
         */
   }

   // @Command(value = "url-plugin",
   // help = "Download and install a plugin from the given URL")
   public void installFromRemoteURL(
            @Option(description = "URL of jar file", required = true) final URL url,
            @Option(name = "id", description = "plugin identifier, [e.g. \"com.example.group : example-plugin\"]", required = true) final Dependency dep,
            final PipeOut out) throws Exception
   {

      throw new IllegalStateException("Not implemented");
      /*
      ShellMessages.info(out, "WARNING!");
      if (prompt.promptBoolean(
               "Installing plugins from remote sources is dangerous, and can leave untracked plugins. Continue?", true))
      {
         FileResource<?> jar = createIncrementedPluginJarFile(dep);
         PluginUtil.downloadFromURL(out, url, jar);
         ShellMessages.success(out, "Installed from [" + url.toExternalForm() + "] successfully.");
         restart();
      }
      else
         throw new RuntimeException("Aborted.");
         */
   }

   @Command(value = "source-plugin",
            help = "Install a plugin from a local project folder")
   public void installFromLocalProject(
            @Option(description = "project directory", required = true) final Resource<?> projectFolder,
            final PipeOut out) throws Exception
   {
      DirectoryResource workspace = projectFolder.reify(DirectoryResource.class);
      if ((workspace == null) || !workspace.exists())
      {
         throw new IllegalArgumentException("Project folder must be specified.");
      }

      buildFromCurrentProject(out, workspace);

      ShellMessages.success(out, "Installed from [" + workspace + "] successfully.");
      // ShellMessages.info(out, "Please restart Forge to complete plugin installation.");
      restart();
   }

   @Command(value = "git-plugin",
            help = "Install a plugin from a public git repository")
   public void installFromGit(
            @Option(description = "git repo", required = true) final String gitRepo,
            @Option(name = "ref", description = "branch or tag to build") final String refName,
            @Option(name = "checkoutDir", description = "directory in which to clone the repository") final Resource<?> checkoutDir,
            final PipeOut out) throws Exception
   {

      DirectoryResource savedLocation = shell.getCurrentDirectory();
      DirectoryResource workspace = savedLocation.createTempResource();

      try
      {
         DirectoryResource buildDir = workspace.getChildDirectory("repo");
         if (checkoutDir != null)
         {
            if (!checkoutDir.exists() && (checkoutDir instanceof FileResource<?>))
            {
               ((FileResource<?>) checkoutDir).mkdirs();
            }
            buildDir = checkoutDir.reify(DirectoryResource.class);
         }

         if (buildDir.exists())
         {
            buildDir.delete(true);
            buildDir.mkdir();
         }

         ShellMessages.info(out, "Checking out plugin source files to [" + buildDir.getFullyQualifiedName()
                  + "] via 'git'");
         Git repo = GitUtils.clone(buildDir, gitRepo);

         Ref ref = null;
         String targetRef = refName;
         if (targetRef == null)
         {
            // Default to Forge runtime version if no Ref name is supplied.
            targetRef = environment.getRuntimeVersion();
         }

         // Try to find a Tag matching the given Ref name or runtime version
         Map<String, Ref> tags = repo.getRepository().getTags();
         ref = tags.get(targetRef);

         // Now try to find a matching Branch
         if (ref == null)
         {
            List<Ref> refs = GitUtils.getBranches(repo);
            for (Ref branchRef : refs) {
               if (branchRef.getName().equals(targetRef))
               {
                  ref = branchRef;
               }
            }
         }

         if (ref != null)
         {
            ShellMessages.info(out, "Switching to branch/tag [" + targetRef + "]");
            GitUtils.checkout(repo, ref, false, SetupUpstreamMode.SET_UPSTREAM, false);
         }
         else if (refName != null)
         {
            throw new RuntimeException("Could not locate ref [" + targetRef + "] in repository ["
                     + repo.getRepository().getDirectory().getAbsolutePath() + "]");
         }
         else
         {
            ShellMessages.warn(out,
                     "Could not find a Ref matching the current Forge version [" + environment.getRuntimeVersion()
                              + "], building Plugin from HEAD.");
         }

         buildFromCurrentProject(out, buildDir);
      }
      finally
      {
         if (checkoutDir != null)
         {
            ShellMessages.info(out,
                     "Cleaning up temp workspace [" + workspace.getFullyQualifiedName()
                              + "]");
            workspace.delete(true);
         }
      }

      ShellMessages.success(out, "Installed from [" + gitRepo + "] successfully.");
      restart();
   }

   /*
    * Helpers
    */
   private void buildFromCurrentProject(final PipeOut out, final DirectoryResource buildDir) throws Abort
   {
      DirectoryResource savedLocation = shell.getCurrentDirectory();
      try
      {
         shell.setCurrentResource(buildDir);
         Project project = shell.getCurrentProject();
         if (project == null)
         {
            throw new IllegalStateException("Unable to recognise plugin project in ["
                     + buildDir.getFullyQualifiedName() + "]");
         }

         String apiVersion = null;
         DependencyFacet deps = project.getFacet(DependencyFacet.class);
         DependencyBuilder shellApi = DependencyBuilder.create("org.jboss.forge:forge-shell-api");
         if (!deps.hasEffectiveDependency(shellApi)
                  && !prompt.promptBoolean("The project does not appear to be a Forge Plugin Project, install anyway?",
                           false))
         {
            throw new Abort("Installation aborted");
         }
         else
         {
            Dependency effectiveDependency = deps.getEffectiveDependency(shellApi);
            if (effectiveDependency != null)
               apiVersion = effectiveDependency.getVersion();
            else
               apiVersion = environment.getRuntimeVersion();
         }

         List<Dependency> dependencies = deps.getDependencies();
         for (Dependency dependency : dependencies) {
            if ("org.jboss.forge".equals(dependency.getGroupId())
                     && !(ScopeType.PROVIDED.equals(dependency.getScopeTypeEnum())
                     || ScopeType.TEST.equals(dependency.getScopeTypeEnum())))
            {
               ShellMessages.warn(out, "Dependency [" + dependency.toCoordinates()
                        + "] was not correctly marked as PROVIDED scope; this has been corrected.");
               deps.addDirectDependency(DependencyBuilder.create(dependency).setScopeType(ScopeType.PROVIDED));
            }
         }

         ShellMessages.info(out, "Invoking build with underlying build system.");
         Resource<?> artifact = project.getFacet(PackagingFacet.class).executeBuild();
         if ((artifact != null) && artifact.exists())
         {
            MetadataFacet meta = project.getFacet(MetadataFacet.class);
            Dependency dep = meta.getOutputDependency();

            ShellMessages.info(out, "Installing plugin artifact.");

            // TODO Figure out a better plugin versioning strategy than random numbers, also see if unloading is
            // possible to avoid this entirely.
            createModule(project,
                     DependencyBuilder.create(dep).setVersion(dep.getVersion() + "-" + UUID.randomUUID().toString()),
                     artifact, apiVersion);
         }
         else
         {
            throw new IllegalStateException("Build artifact [" + artifact.getFullyQualifiedName()
                     + "] is missing and cannot be installed. Please resolve build errors and try again.");
         }
      }
      finally
      {
         shell.setCurrentResource(savedLocation);
      }
   }

   private DirectoryResource createModule(final Project project, final Dependency dep, final Resource<?> artifact,
            final String apiVersion)
   {
      DirectoryResource moduleDir = getOrCreatePluginModuleDirectory(dep);
      String pluginName = dep.getGroupId() + "." + dep.getArtifactId();
      String pluginSlot = dep.getVersion();

      FileResource<?> moduleXml = (FileResource<?>) moduleDir.getChild("module.xml");
      if (moduleXml.exists()
               && !prompt.promptBoolean(
                        "An existing installation for version [" + pluginSlot
                                 + "] of this plugin was found. Replace it?", true))
      {
         throw new RuntimeException("Aborted.");
      }

      moduleXml.delete();
      moduleXml.createNewFile();

      // <resource-root path="maven-dependency.jar" />
      Node module = XMLParser.parse(getClass().getResourceAsStream(MODULE_TEMPLATE_XML));
      module.attribute("name", pluginName);
      module.attribute("slot", pluginSlot);
      Node resources = module.getSingle("resources");

      resources.createChild("resource-root").attribute("path", dep.getArtifactId() + ".jar");

      // Copy the compiled JAR into the module directory
      FileResource<?> jar = moduleDir.getChild(dep.getArtifactId() + ".jar").reify(FileResource.class);
      jar.createNewFile();
      jar.setContents(artifact.getResourceInputStream());

      // <module name="org.jboss.forge:main" />
      Node dependencies = module.getSingle("dependencies");
      dependencies.createChild("module").attribute("name", pluginName + ".dependencies").attribute("slot", pluginSlot);
      dependencies.createChild("module").attribute("name", "org.jboss.forge.javaee.api")
               .attribute("services", "import");
      dependencies.createChild("module").attribute("name", "org.jboss.forge.maven.api").attribute("services", "import");
      dependencies.createChild("module").attribute("name", "org.jboss.forge.scaffold.api")
               .attribute("services", "import");
      dependencies.createChild("module").attribute("name", "org.jboss.forge.shell.api").attribute("services", "import");
      dependencies.createChild("module").attribute("name", "javax.api");

      moduleXml.setContents(XMLParser.toXMLString(module));

      createDependenciesModule(project, dep);

      // Add to list modules.
      registerPlugin(pluginName, pluginSlot, apiVersion);

      return moduleDir;
   }

   private void createDependenciesModule(final Project project, final Dependency dep)
   {
      DirectoryResource dependencyDir = getOrCreatePluginDependenciesModuleDirectory(dep);
      String pluginName = dep.getGroupId() + "." + dep.getArtifactId();
      String pluginSlot = dep.getVersion();

      DependencyFacet deps = project.getFacet(DependencyFacet.class);

      FileResource<?> moduleXml = (FileResource<?>) dependencyDir.getChild("module.xml");
      moduleXml.delete();
      moduleXml.createNewFile();

      // <resource-root path="maven-dependency.jar" />
      Node module = XMLParser.parse(getClass().getResourceAsStream(MODULE_TEMPLATE_XML));
      module.attribute("name", pluginName + ".dependencies");
      module.attribute("slot", pluginSlot);
      Node resources = module.getSingle("resources");

      // <module name="org.jboss.forge:main" />
      Node dependencies = module.getSingle("dependencies");
      dependencies.createChild("module").attribute("name", "javax.api");
      dependencies.createChild("module").attribute("name", "org.jboss.forge.shell.api");

      List<DependencyResource> pluginDependencies = new ArrayList<DependencyResource>();
      List<Dependency> effectiveDependenciesInScopes = deps.getEffectiveDependenciesInScopes(ScopeType.COMPILE,
               ScopeType.RUNTIME);
      for (Dependency d : effectiveDependenciesInScopes) {
         if (d.getPackagingTypeEnum().equals(PackagingType.JAR)
                  && !d.getGroupId().equals("org.jboss.forge"))
         {
            List<DependencyResource> artifacts = resolveArtifacts(project, d);
            pluginDependencies.addAll(artifacts);
         }
         // TODO encapsulate this?
         if (DependencyBuilder.areEquivalent(d, DependencyBuilder.create("org.jboss.forge:forge-javaee-api")))
         {
            module.getSingle("dependencies").createChild("module")
                     .attribute("name", "org.jboss.forge.javaee.api")
                     .attribute("services", "import");
         }
         if (DependencyBuilder.areEquivalent(d, DependencyBuilder.create("org.jboss.forge:forge-scaffold-api")))
         {
            module.getSingle("dependencies").createChild("module")
                     .attribute("name", "org.jboss.forge.scaffold.api")
                     .attribute("services", "import");
         }
         if (DependencyBuilder.areEquivalent(d, DependencyBuilder.create("org.jboss.forge:forge-maven-api")))
         {
            module.getSingle("dependencies").createChild("module")
                     .attribute("name", "org.jboss.forge.maven.api")
                     .attribute("services", "import");
         }
      }

      // Copy dependencies into module
      for (DependencyResource d : pluginDependencies) {
         String name = d.getName();
         Resource<?> child = dependencyDir.getChild(name);
         child.delete();
         FileResource<?> depJar = child.reify(FileResource.class);
         depJar.setContents(d.getResourceInputStream());
         resources.createChild("resource-root").attribute("path", name);
      }

      // Write out the module XML file.
      moduleXml.setContents(XMLParser.toXMLString(module));
   }

   private List<DependencyResource> resolveArtifacts(final Project project, final Dependency dep)
   {
      Dependency d = dep;

      List<DependencyResource> artifacts = new ArrayList<DependencyResource>();
      DependencyFacet deps = project.getFacet(DependencyFacet.class);

      for (Dependency d2 : deps.getDependencies()) {
         if (DependencyBuilder.areEquivalent(d, d2) && (d2.getVersion() != null))
         {
            d = d2;
            break;
         }
      }

      if (artifacts.size() != 1)
      {
         artifacts = resolver.resolveArtifacts(d, deps.getRepositories());
      }
      if (artifacts.size() != 1)
      {
         ShellMessages.warn(writer, "Could not resolve dependency [" + d.toCoordinates() + "]");
      }

      return artifacts;
   }

   public void registerPlugin(final String pluginName, final String pluginSlot, final String apiVersion)
   {
      InstalledPluginRegistry.install(pluginName, apiVersion, pluginSlot);
   }

   public DirectoryResource getOrCreatePluginModuleDirectory(final Dependency dep)
   {
      DirectoryResource pluginDir = environment.getPluginDirectory();

      List<String> groupId = Arrays.asList(dep.getGroupId().split("\\."));
      List<String> artifactId = Arrays.asList(dep.getArtifactId().split("\\."));
      DirectoryResource dir = pluginDir;
      for (String segment : groupId) {
         dir = dir.getOrCreateChildDirectory(segment);
      }

      for (String segment : artifactId) {
         dir = dir.getOrCreateChildDirectory(segment);
      }

      dir = dir.getOrCreateChildDirectory(dep.getVersion());
      return dir;
   }

   public DirectoryResource getOrCreatePluginDependenciesModuleDirectory(final Dependency dep)
   {
      DirectoryResource pluginDir = environment.getPluginDirectory();

      List<String> groupId = Arrays.asList(dep.getGroupId().split("\\."));
      List<String> artifactId = Arrays.asList(dep.getArtifactId().split("\\."));
      DirectoryResource dir = pluginDir;
      for (String segment : groupId) {
         dir = dir.getOrCreateChildDirectory(segment);
      }

      for (String segment : artifactId) {
         dir = dir.getOrCreateChildDirectory(segment);
      }

      dir = dir.getOrCreateChildDirectory("dependencies");
      dir = dir.getOrCreateChildDirectory(dep.getVersion());
      return dir;
   }
}
