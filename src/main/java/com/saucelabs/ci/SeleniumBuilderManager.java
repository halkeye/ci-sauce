package com.saucelabs.ci;

import com.sebuilder.interpreter.Script;
import com.sebuilder.interpreter.SeInterpreter;
import com.sebuilder.interpreter.factory.ScriptFactory;
import com.sebuilder.interpreter.factory.StepTypeFactory;
import com.sebuilder.interpreter.factory.TestRunFactory;
import com.sebuilder.interpreter.webdriverfactory.Firefox;
import com.sebuilder.interpreter.webdriverfactory.Remote;
import com.sebuilder.interpreter.webdriverfactory.WebDriverFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides integration with the {@link com.sebuilder.interpreter.SeInterpreter} class for running
 * JSON-based Selenium Builder scripts
 *
 * @author Ross Rowe
 */
public class SeleniumBuilderManager {

    public static final String BROWSER_NAME = "browserName";
    public static final String VERSION = "version";

    private static final Logger logger = Logger.getLogger(SeleniumBuilderManager.class.getName());

    /**
     * TODO always use wd/hub?
     */
    private static final String URL = "http://{0}:{1}@{2}:{3}/wd/hub";
    private static final String PLATFORM = "platform";
    private static final String NAME = "name";
    private static final String URL_KEY = "url";

    /**
     * Creates and invokes a new {@link Script} instance for the given <code>scriptFile</code>.
     *
     * @param scriptFile     The se-builder script file to invoke
     * @param envVars        Contains the available environment variables set by the CI environment.  The Sauce CI
     *                       plugin will set envvars that contain information about the browser/user/url to use.
     * @param printStream    Where messages will be logged to
     * @param threadPoolSize the size of the thread pool to be used when running tests in parallel, can be null
     * @return boolean indicating whether the invocation of the se-builder script was successful
     */
    public boolean executeSeleniumBuilder(File scriptFile, Map envVars, PrintStream printStream, Integer threadPoolSize) {


        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize == null ? 1 : threadPoolSize);
        try {
            HashMap<String, String> config = new HashMap<String, String>();
            String browserInformation = readPropertyOrEnv("SAUCE_ONDEMAND_BROWSERS", envVars, null);
            if (browserInformation != null) {
                //multiple browsers defined, parse JSON data
                JSONArray browserArray = new JSONArray(browserInformation);
                boolean result = true;
                for (int i = 0; i < browserArray.length(); i++) {
                    JSONObject browserObject = browserArray.getJSONObject(i);
                    String browser = browserObject.getString("browser");
                    String version = browserObject.getString("browser-version");
                    String os = browserObject.getString("os");
                    result = invokeBuild(executorService, scriptFile, envVars, printStream, config, browser, version, os, threadPoolSize) || result;
                }
                return result;
            } else {
                String browser = readPropertyOrEnv("SELENIUM_BROWSER", envVars, null);
                String version = readPropertyOrEnv("SELENIUM_VERSION", envVars, null);
                String os = readPropertyOrEnv("SELENIUM_PLATFORM", envVars, null);
                return invokeBuild(executorService, scriptFile, envVars, printStream, config, browser, version, os, threadPoolSize);
            }
        } catch (Exception e) {
            printStream.println("Error running selenium builder command");
            SeleniumBuilderManager.logger.log(Level.WARNING, "Error running selenium builder command", e);
        } finally {
            executorService.shutdown();
            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                printStream.println("InterruptedException running selenium builder command");
                SeleniumBuilderManager.logger.log(Level.WARNING, "InterruptedException running selenium builder command", e);
            }
        }
        return false;
    }

    private boolean invokeBuild(ExecutorService executorService, File scriptFile, Map envVars, final PrintStream printStream, final HashMap<String, String> config, String browser, String version, String os, final Integer threadPoolSize) throws IOException, JSONException {
        String host = readPropertyOrEnv("SELENIUM_HOST", envVars, null);
        String port = readPropertyOrEnv("SELENIUM_PORT", envVars, null);
        String username = readPropertyOrEnv("SAUCE_USER_NAME", envVars, null);
        String accessKey = readPropertyOrEnv("SAUCE_API_KEY", envVars, null);

        if (browser != null) {
            config.put(BROWSER_NAME, browser);
        }
        if (version != null) {
            config.put(VERSION, version);
        }
        if (os != null) {
            config.put(PLATFORM, os);
        }
        config.put(NAME, scriptFile.getName());
        if (host != null && accessKey != null && username != null && port != null)
            config.put(URL_KEY, MessageFormat.format(URL, username, accessKey, host, port));

        final Log log = LogFactory.getFactory().getInstance(SeInterpreter.class);

        BufferedReader br = null;

        try {
            ScriptFactory sf = new ScriptFactory();
            StepTypeFactory stf = new StepTypeFactory();
            sf.setStepTypeFactory(stf);
            TestRunFactory trf = new TestRunFactory();
            sf.setTestRunFactory(trf);
            printStream.println("Starting to run selenium builder command");
            final WebDriverFactory remoteDriver = createRemoteDriver(config.get(URL_KEY), printStream);
            for (final Script script : sf.parse(scriptFile)) {
                executorService.submit(new Runnable() {
                    public void run() {
                        WebDriverFactory driver = remoteDriver;
                        if (threadPoolSize != null && threadPoolSize > 1) {
                            //if we are running multiple threads, then create a new driver per test
                            driver = createRemoteDriver(config.get(URL_KEY), printStream);
                        }
                        script.run(new PrintStreamLogger(log, printStream), driver, config);
                    }
                });
            }
            return true;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    SeleniumBuilderManager.logger.log(Level.WARNING, "Error closing script file", e);
                }
            }
        }
    }

    /**
     * Returns the {@link WebDriverFactory} instance to be used to run the script against.
     *
     * @param url
     * @return
     */
    private WebDriverFactory createRemoteDriver(String url, PrintStream printStream) {
        if (url == null || url.equals("")) {
            //run against firefox
            return new Firefox();
        } else {
            return new SauceRemoteDriver(printStream);
        }
    }

    private String readPropertyOrEnv(String key, Map vars, String defaultValue) {
        String v = (String) vars.get(key);
        if (v == null)
            v = System.getProperty(key);
        if (v == null)
            v = System.getenv(key);
        if (v == null)
            v = defaultValue;
        return v;
    }

    /**
     * {@link Log} implementation which logs messages to the underlying Logger as well as to the PrintStream.
     */
    private class PrintStreamLogger implements Log {

        private Log log;
        private PrintStream printStream;

        public PrintStreamLogger(Log log, PrintStream printStream) {
            this.log = log;
            this.printStream = printStream;
        }

        public boolean isDebugEnabled() {
            return log.isDebugEnabled();
        }

        public boolean isErrorEnabled() {
            return log.isErrorEnabled();
        }

        public boolean isFatalEnabled() {
            return log.isFatalEnabled();
        }

        public boolean isInfoEnabled() {
            return log.isInfoEnabled();
        }

        public boolean isTraceEnabled() {
            return log.isTraceEnabled();
        }

        public boolean isWarnEnabled() {
            return log.isTraceEnabled();
        }

        public void trace(Object o) {
            log.trace(o);
            if (log.isTraceEnabled()) {
                printStream.println(o);
            }
        }

        public void trace(Object o, Throwable throwable) {
            log.trace(o, throwable);
            if (log.isTraceEnabled()) {
                printStream.println(o);
            }
        }

        public void debug(Object o) {
            log.debug(o);
            printStream.println(o);

        }

        public void debug(Object o, Throwable throwable) {
            log.debug(o, throwable);
            printStream.println(o);

        }

        public void info(Object o) {
            log.info(o);
            printStream.println(o);

        }

        public void info(Object o, Throwable throwable) {
            log.info(o, throwable);
            printStream.println(o);

        }

        public void warn(Object o) {
            log.warn(o);
            printStream.println(o);

        }

        public void warn(Object o, Throwable throwable) {
            log.warn(o, throwable);
            printStream.println(o);

        }

        public void error(Object o) {
            log.error(o);
            printStream.println(o);

        }

        public void error(Object o, Throwable throwable) {
            log.error(o, throwable);
            printStream.println(o);

        }

        public void fatal(Object o) {
            log.fatal(o);
            printStream.println(o);

        }

        public void fatal(Object o, Throwable throwable) {
            log.fatal(o, throwable);
            printStream.println(o);

        }
    }

    /**
     * {@link Remote} subclass which prints a log message identifying the Sauce session id, which will be
     * picked up by the Sauce CI plugin.
     */
    private class SauceRemoteDriver extends Remote {

        private RemoteWebDriver driver;

        public static final String PLATFORM = "platform";
        private PrintStream printStream;

        public SauceRemoteDriver(PrintStream printStream) {
            this.printStream = printStream;
        }

        @Override
        public RemoteWebDriver make(HashMap<String, String> config) throws MalformedURLException {

            if (driver != null) {
                return driver;
            }

            java.net.URL url = config.containsKey(URL_KEY)
                    ? new URL(config.get(URL_KEY))
                    : null;
            HashMap<String, String> caps = new HashMap<String, String>(config);
            caps.get(URL_KEY);
            DesiredCapabilities capabilities = new DesiredCapabilities();
            capabilities.setCapability(CapabilityType.BROWSER_NAME, config.get(BROWSER_NAME));
            if (config.containsKey(VERSION)) {
                capabilities.setCapability(CapabilityType.VERSION, config.get(VERSION));
            }
            capabilities.setCapability(CapabilityType.PLATFORM, config.get(PLATFORM));
            capabilities.setCapability(NAME, config.get(NAME));
            driver = new RemoteWebDriver(url, capabilities);

            driver.manage().timeouts().implicitlyWait(30, TimeUnit.SECONDS);
            printStream.println(MessageFormat.format("SauceOnDemandSessionID={0} job-name={1}", driver.getSessionId(), config.get("name")));
            return driver;
        }
    }
}
