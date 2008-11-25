/*
 * Copyright (c)2005-2008 Mark Logic Corporation
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
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.developer.corb;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import com.marklogic.developer.SimpleLogger;
import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.Content;
import com.marklogic.xcc.ContentCreateOptions;
import com.marklogic.xcc.ContentFactory;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.ContentSourceFactory;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultItem;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;
import com.marklogic.xcc.exceptions.XccException;
import com.marklogic.xcc.types.XSInteger;
import com.marklogic.xcc.types.XdmItem;

/**
 * @author Michael Blakeley, michael.blakeley@marklogic.com
 * @author Colleen Whitney, colleen.whitney@marklogic.com
 * 
 */
public class Manager implements Runnable {

    public static String VERSION = "2008-11-24.1";

    private static String versionMessage = "version " + VERSION + " on "
            + System.getProperty("java.version") + " ("
            + System.getProperty("java.runtime.name") + ")";

    /**
     * 
     */
    private static final String DECLARE_NAMESPACE_MLSS_XDMP_STATUS_SERVER = "declare namespace mlss = 'http://marklogic.com/xdmp/status/server'\n";

    /**
     * 
     */
    private static final String XQUERY_VERSION_0_9_ML = "xquery version \"0.9-ml\"\n";

    /**
     * 
     */
    private static final String NAME = Manager.class.getName();

    private URI connectionUri;

    private String collection;

    private TransformOptions options = new TransformOptions();

    private ThreadPoolExecutor pool = null;

    private ContentSource contentSource;

    private Task[] transforms;

    private Monitor monitor;

    private SimpleLogger logger;

    private String moduleUri;

    private Thread monitorThread;

    /**
     * @param connectionUri
     * @param collection
     * @param modulePath
     * @param uriListPath
     */
    public Manager(URI connectionUri, String collection) {
        this.connectionUri = connectionUri;
        this.collection = collection;
    }

    /**
     * @param args
     * @throws URISyntaxException
     */
    public static void main(String[] args) throws URISyntaxException {
        if (args.length < 3) {
            usage();
            return;
        }

        // gather inputs
        URI connectionUri = new URI(args[0]);
        String collection = args[1];

        Manager tm = new Manager(connectionUri, collection);
        // options
        TransformOptions options = tm.getOptions();

        options.setProcessModule(args[2]);

        if (args.length > 3 && !args[3].equals("")) {
            options.setThreadCount(Integer.parseInt(args[3]));
        }
        if (args.length > 4 && !args[4].equals("")) {
            options.setUrisModule(args[4]);
        }
        if (args.length > 5 && !args[5].equals("")) {
            options.setModuleRoot(args[5]);
        }
        if (args.length > 6 && !args[6].equals("")) {
            options.setModulesDatabase(args[6]);
        }
        if (args.length > 7 && !args[7].equals("")) {
            if (args[7].equals("false") || args[7].equals("0"))
                options.setDoInstall(false);
        }
        tm.run();
    }

    /**
     * @return
     */
    private TransformOptions getOptions() {
        return options;
    }

    /**
     * 
     */
    private static void usage() {
        PrintStream err = System.err;
        err.println("\nusage:");
        err.println("\t" + NAME
                + " xcc://user:password@host:port/[ database ]"
                + " input-selector module-name.xqy"
                + " [ thread-count [ uris-module [ module-root"
                + " [ modules-database [ install ] ] ] ] ]");
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    public void run() {
        configureLogger();
        logger.info(NAME + " starting: " + versionMessage);
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        logger.info("heap size = " + maxMemory + " MiB");

        prepareContentSource();
        registerStatusInfo();
        prepareModules();
        monitorThread = preparePool();

        try {
            populateQueue();

            while (monitorThread.isAlive()) {
                try {
                    monitorThread.join();
                } catch (InterruptedException e) {
                    logger.logException(
                            "interrupted while waiting for monitor", e);
                }
            }
        } catch (XccException e) {
            logger.logException(connectionUri.toString(), e);
            // fatal
            throw new RuntimeException(e);
        } finally {
            stop();
        }
    }

    /**
     * @return
     */
    private Thread preparePool() {
        pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(options
                .getThreadCount());
        monitor = new Monitor(pool, this, logger);
        Thread monitorThread = new Thread(monitor);
        return monitorThread;
    }

    /**
     * @throws IOException
     * @throws RequestException
     * 
     */
    private void prepareModules() {
        String[] resourceModules = new String[] {
                options.getUrisModule(), options.getProcessModule() };
        String modulesDatabase = options.getModulesDatabase();
        logger.info("checking modules, database: " + modulesDatabase);
        Session session = contentSource.newSession(modulesDatabase);
        InputStream is = null;
        Content c = null;
        ContentCreateOptions opts = ContentCreateOptions
                .newTextInstance();
        try {
            for (int i = 0; i < resourceModules.length; i++) {
                // Start by checking install flag.
                if (!options.isDoInstall()) {
                    logger.info("Skipping module installation: "
                            + resourceModules[i]);
                    continue;
                }
                // Next check: if XCC is configured for the filesystem, warn
                // user
                else if (options.getModulesDatabase().equals("")) {
                    logger
                            .warning("XCC configured for the filesystem: please install modules manually");
                    return;
                }
                // Finally, if it's configured for a database, install.
                else {
                    File f = new File(resourceModules[i]);
                    // If not installed, are the specified files on the
                    // filesystem?
                    if (f.exists()) {
                        moduleUri = options.getModuleRoot() + f.getName();
                        c = ContentFactory.newContent(moduleUri, f, opts);
                    }
                    // finally, check package
                    else {
                        logger.warning("looking for "
                                + resourceModules[i] + " as resource");
                        moduleUri = options.getModuleRoot()
                                + resourceModules[i];
                        is = this.getClass().getResourceAsStream(
                                resourceModules[i]);
                        if (null == is) {
                            throw new NullPointerException(
                                    resourceModules[i]
                                            + " could not be found on the filesystem,"
                                            + " or in package resources");
                        }
                        c = ContentFactory
                                .newContent(moduleUri, is, opts);
                    }
                    session.insertContent(c);
                }
            }
        } catch (IOException e) {
            logger.logException("fatal error", e);
            throw new RuntimeException(e);
        } catch (RequestException e) {
            logger.logException("fatal error", e);
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
    }

    /**
     * 
     */
    private void prepareContentSource() {
        logger.info("using content source " + connectionUri);
        try {
            contentSource = ContentSourceFactory
                    .newContentSource(connectionUri);
        } catch (XccConfigException e1) {
            logger.logException(connectionUri.toString(), e1);
            throw new RuntimeException(e1);
        }
    }

    private void registerStatusInfo() {
        Session session = contentSource.newSession();
        AdhocQuery q = session.newAdhocQuery(XQUERY_VERSION_0_9_ML
                + DECLARE_NAMESPACE_MLSS_XDMP_STATUS_SERVER
                + "let $status := \n"
                + " xdmp:server-status(xdmp:host(), xdmp:server())\n"
                + "let $modules := $status/mlss:modules\n"
                + "let $root := $status/mlss:root\n"
                + "return (data($modules), data($root))");
        ResultSequence rs = null;
        try {
            rs = session.submitRequest(q);
        } catch (RequestException e) {
            e.printStackTrace();
        } finally {
            session.close();
        }
        while (rs.hasNext()) {
            ResultItem rsItem = rs.next();
            XdmItem item = rsItem.getItem();
            if (rsItem.getIndex() == 0 && item.asString().equals("0")) {
                options.setModulesDatabase("");
            }
            if (rsItem.getIndex() == 1) {
                options.setXDBC_ROOT(item.asString());
            }
        }
        logger.info("Configured modules db: "
                + options.getModulesDatabase());
        logger.info("Configured modules root: " + options.getXDBC_ROOT());
    }

    /**
     * @throws XccException
     */
    private void populateQueue() throws XccException {
        logger.info("populating queue");
        
        TaskFactory tf = new TaskFactory(contentSource, options.getModuleRoot()
                + options.getProcessModule());

        // must run uncached, or we'll quickly run out of memory
        RequestOptions requestOptions = new RequestOptions();
        requestOptions.setCacheResult(false);

        Session session = null;
        int count = 0;
        int total = -1;

        try {
            session = contentSource.newSession();
            String urisModule = options.getModuleRoot()
                    + options.getUrisModule();
            logger.info("invoking module " + urisModule);
            Request req = session.newModuleInvoke(urisModule);
            // NOTE: collection will be treated as a CWSV
            req.setNewStringVariable("URIS", collection);
            // TODO support DIRECTORY as type
            req.setNewStringVariable("TYPE",
                    TransformOptions.COLLECTION_TYPE);
            req.setNewStringVariable("PATTERN", "[,\\s]+");
            req.setOptions(requestOptions);

            ResultSequence res = session.submitRequest(req);

            // like a pascal string, the first item will be the count
            total = ((XSInteger) res.next().getItem()).asPrimitiveInt();
            logger.info("expecting total " + total);
            if (0 == total) {
                logger.info("nothing to process");
                stop();
                return;
            }
            monitorThread.start();
            transforms = new Task[total];

            // the monitor needs access to this structure too
            monitor.setTasks(transforms);

            // this may return millions of items:
            // try to be memory-efficient
            count = 0;
            Task transform;
            String uri;
            // check pool occasionally, for fast-fail
            while (res.hasNext() && null != pool) {
                uri = res.next().asString();
                transform = tf.newTask(uri);
                transforms[count] = transform;
                if (null == pool) {
                    break;
                }
                pool.submit(transform);
                count++;
                String msg = "queued " + count + "/" + total + ": " + uri;
                if (0 == count % 10000) {
                    logger.info(msg);
                } else {
                    logger.finest(msg);
                }
            }
        } catch (XccException e) {
            if (null != pool) {
                pool.shutdownNow();
            }
            stop();
            throw e;
        } finally {
            if (null != session) {
                session.close();
            }
            if (null != pool) {
                pool.shutdown();
            }
        }
        // if the pool went away, the monitor stopped it: bail out.
        if (null == pool) {
            return;
        }
        assert total == count;

        // there won't be any more tasks
        pool.shutdown();
        logger.info("queue is fully populated with " + total + " tasks");
    }

    private void configureLogger() {
        if (logger == null) {
            logger = SimpleLogger.getSimpleLogger();
        }
        Properties props = new Properties();
        props.setProperty("LOG_LEVEL", options.getLogLevel());
        props.setProperty("LOG_HANDLER", options.getLogHandler());
        logger.configureLogger(props);
    }

    /**
     * @param e
     */
    public void stop() {
        if (pool != null) {
            List<Runnable> remaining = pool.shutdownNow();
            if (remaining.size() > 0) {
                logger.warning("thread pool was shut down with "
                        + remaining.size() + " pending tasks");
            }
            pool = null;
        }
        if (null != monitor) {
            monitor.setPool(null);
        }
        if (null != monitorThread) {
            monitorThread.interrupt();
        }
    }

    /**
     * @param e
     * @param transform
     */
    public void stop(ExecutionException e, Transform transform) {
        // fatal error
        logger.logException("fatal error at result for input document "
                + transform.getUri(), e.getCause());
        logger.warning("exiting due to fatal error");
        stop();
    }
}
