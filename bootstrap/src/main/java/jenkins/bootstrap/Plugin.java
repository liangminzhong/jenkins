/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.bootstrap;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Delete;
import org.apache.tools.ant.taskdefs.Expand;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

/**
 * Plugin that we process during the bootstrap.
 *
 * <p>
 * In order to identify the core component plugin that overrides Jenkins core and put them
 * into the classpath, the bootstrap logic needs to inspect plugins lightly.
 * Jenkins will later do further full treatment of plugins.
 *
 * <p>
 * Some of the capabilities of the full plugin manager is missing from this code. For example,
 * the bootstrap doesn't handle {@code *.jpl} file used during the development.
 *
 * @author Kohsuke Kawaguchi
 */
class Plugin {
    /**
     * foo.jpi file
     */
    private final File archive;
    /**
     * Directory to explode the plugin.
     */
    private final File destDir;

    Plugin(File archive) {
        this.archive = archive;
        this.destDir = getDestDir();
    }

    void process(DependenciesTxt core, List<URL> jars, List<DependenciesTxt> overrides) throws IOException {
        if (!isCoreComponentOverride() || isDisabled())
            return;

        explode();

        File dependencies = new File(destDir,"META-INF/dependencies.txt");
        try (InputStream i = new FileInputStream(dependencies)) {
            DependenciesTxt deps = new DependenciesTxt(i);
            for (Dependency d : deps.dependencies) {
                if (core.hasNewerThan(d)) {
                    // core has a newer version. abort.
                    LOGGER.warning("Skipping core override plugin "+archive+" because core contains a newer version of "+d.ga);
                    return;
                }
            }
            overrides.add(deps);
        }


        File lib = new File(destDir, "WEB-INF/lib");
        LOGGER.info("Loading core overrides from "+lib);
        File[] files = lib.listFiles();
        if (files!=null) {
            for (File jar : files) {
                if (!jar.getName().endsWith(".jar"))
                    continue;
                // TODO: version check
                jars.add(jar.toURI().toURL());
            }
        }
    }

    /**
     * Gets the directory name to explode the jpi file into.
     */
    private File getDestDir() {
        String name = archive.getName();
        int idx = name.lastIndexOf('.');
        if (idx<0)  throw new IllegalArgumentException("No extension in file name: "+archive);
        return new File(archive.getParentFile(), name.substring(0,idx));
    }

    /**
     * Returns true if this plugin overrides core components.
     */
    private boolean isCoreComponentOverride() throws IOException {
        Manifest mf = loadManifest();
        return "true".equals(mf.getMainAttributes().getValue("Core-Component"));
    }

    public boolean isDisabled() {
        File disableFile = new File(archive.getPath() + ".disabled");
        return disableFile.exists();
    }

    /**
     * Loads jar file manifest from the archive.
     */
    private Manifest loadManifest() throws IOException {
        try (JarFile jar = new JarFile(archive)) {
            ZipEntry e = jar.getEntry("META-INF/MANIFEST.MF");
            try (InputStream is = jar.getInputStream(e)) {
                return new Manifest(is);
            }
        }
    }

    /**
     * Unpacks {@link #archive} into {@link #destDir} with uptodate check.
     *
     * <p>
     * Up-to-date check needs to be consistent with {@coe ClassicPluginStrategy} in core.
     *
     * <p>
     * This simplified version does not support automatic building of classes.jar from WEB-INF/classes.
     * Pluggable core components are not supposed to have any classes, just WEB-INF/lib files.
     */
    private void explode() throws IOException {
        destDir.mkdirs();

        // timestamp check
        File explodeTime = new File(destDir,".timestamp2");
        if(explodeTime.exists() && explodeTime.lastModified()==archive.lastModified())
            return; // no need to expand

        Project p = new Project();

        if (destDir.exists()) {
            // delete the contents so that old files won't interfere with new files
            Delete d = new Delete();
            d.setProject(p);
            if (destDir.isDirectory())
                d.setDir(destDir);
            else
                d.setFile(destDir);
            d.execute();
        }

        // expand the contents
        Expand e = new Expand();
        e.setProject(p);
        e.setTaskType("unzip");
        e.setSrc(archive);
        e.setDest(destDir);
        e.execute();

        if (new File(destDir,"WEB-INF/classes").exists()) {
            throw new IOException("Core override plugin cannot have WEB-INF/classes: "+destDir);
        }

        // update timestamp file
        new FileOutputStream(explodeTime).close();
        explodeTime.setLastModified(archive.lastModified());
    }

    private static final Logger LOGGER = Logger.getLogger(Plugin.class.getName());
}