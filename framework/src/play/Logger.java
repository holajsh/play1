package play;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import play.exceptions.PlayException;

/**
 * Main logger of the application.
 * Free to use from the application code.
 */
public class Logger {

    /**
     * Will force use of java.util.logging (default to try log4j first).
     */
    public static boolean forceJuli = false;
    /**
     * Will redirect all log from java.util.logging to log4j.
     */
    public static boolean redirectJuli = false;
    /**
     * Will record and display the caller method.
     */
    public static boolean recordCaller = false;
    /**
     * The application logger (play).
     */
    public static org.apache.logging.log4j.Logger log4j;
    /**
     * When using java.util.logging.
     */
    public static java.util.logging.Logger juli = java.util.logging.Logger.getLogger("play");
    /**
     * true if logger is configured manually (log4j-config file supplied by application)
     */
    public static boolean configuredManually = false;

    public static boolean usesJuli() { return forceJuli || log4j == null; }

    /**
     * Try to init stuff.
     */
    public static void init(){
        init(new LoggerInit());
    }

    static void init(LoggerInit init) {
        final URL log4jConf = init.getLog4jConf();
        if (log4jConf == null) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            final Configuration configuration = context.getConfiguration();
            configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME).setLevel(org.apache.logging.log4j.Level.OFF);
            context.reconfigure();
        } else if (Logger.log4j == null) {
            try {
                if (init.access()) {
                    Configurator.reconfigure(log4jConf.toURI());
                    configuredManually = true;
                    Logger.log4j = LogManager.getLogger("play");
                }
            } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException | URISyntaxException ignored) {
            }
            if (Logger.log4j == null) {
                Logger.warn("auto configuration log4j2");
                Logger.log4j = LogManager.getLogger("play");
            }
            // In test mode, append logs to test-result/application.log
            if (Play.runningInTestMode()) {
                try {
                    if (!Play.getFile("test-result").exists()) {
                        Play.getFile("test-result").mkdir();
                    }
                    addAppenderTest();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void addAppenderTest() {
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        final Configuration configuration = context.getConfiguration();
        final FileAppender appender = FileAppender.newBuilder().withAppend(true).setName("test-result")
                                                  .withFileName(Play.getFile("test-result/application.log").getAbsolutePath())
                                                  .setLayout(PatternLayout.newBuilder().withPattern("%d{DATE} %-5p ~ %m%n").build())
                                                  .setConfiguration(configuration)
                                                  .build();
        appender.start();
        configuration.addAppender(appender);
        for (final LoggerConfig loggerConfig : configuration.getLoggers().values()) {
            loggerConfig.addAppender(appender, loggerConfig.getLevel(), loggerConfig.getFilter());
        }
        configuration.getRootLogger().addAppender(appender, null, null);
    }

    /**
     * Force logger to a new level.
     * @param level TRACE,DEBUG,INFO,WARN,ERROR,FATAL
     */
    public static void setUp(String level) {
        if (forceJuli || log4j == null) {
            Logger.juli.setLevel(toJuliLevel(level));
        } else {
            Configurator.setRootLevel(org.apache.logging.log4j.Level.toLevel(level));
            if (redirectJuli) {
                java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
                for (Handler handler : rootLogger.getHandlers()) {
                    rootLogger.removeHandler(handler);
                }
                Handler activeHandler = new JuliToLog4jHandler();
                java.util.logging.Level juliLevel = toJuliLevel(level);
                activeHandler.setLevel(juliLevel);
                rootLogger.addHandler(activeHandler);
                rootLogger.setLevel(juliLevel);
            }
        }
    }

    /**
     * Utility method that translate log4j levels to java.util.logging levels.
     */
    static java.util.logging.Level toJuliLevel(String level) {
        java.util.logging.Level juliLevel = java.util.logging.Level.INFO;
        if (level.equals("ERROR") || level.equals("FATAL")) {
            juliLevel = java.util.logging.Level.SEVERE;
        }
        if (level.equals("WARN")) {
            juliLevel = java.util.logging.Level.WARNING;
        }
        if (level.equals("DEBUG")) {
            juliLevel = java.util.logging.Level.FINE;
        }
        if (level.equals("TRACE")) {
            juliLevel = java.util.logging.Level.FINEST;
        }
        if (level.equals("ALL")) {
            juliLevel = java.util.logging.Level.ALL;
        }
        if (level.equals("OFF")) {
            juliLevel = java.util.logging.Level.OFF;
        }
        return juliLevel;
    }

    /**
     * @return true if log4j.debug / jul.fine logging is enabled
     */
    public static boolean isDebugEnabled() {
        if (forceJuli || log4j == null) {
            return juli.isLoggable(java.util.logging.Level.FINE);
        } else {
            return log4j.isDebugEnabled();
        }
    }
    

    /**
     * @return true if log4j.trace / jul.finest logging is enabled
     */
    public static boolean isTraceEnabled() {
        if (forceJuli || log4j == null) {
            return juli.isLoggable(java.util.logging.Level.FINEST);
        } else {
            return log4j.isTraceEnabled();
        }
    }

    /**
     *
     * @param level string representation of Logging-levels as used in log4j
     * @return true if specified logging-level is enabled
     */
    public static boolean isEnabledFor(String level) {
        //go from level-string to log4j-level-object
        org.apache.logging.log4j.Level log4jLevel = org.apache.logging.log4j.Level.toLevel(level);
        return isEnabledFor(log4jLevel);
    }
    
    /**
    *
    * @param log4jLevel Logging-levels as used in log4j
    * @return true if specified logging-level is enabled
    */
   public static boolean isEnabledFor(org.apache.logging.log4j.Level log4jLevel) {
       if (forceJuli || log4j == null) {
           //must translate from log4j-level to jul-level
           java.util.logging.Level julLevel = toJuliLevel(log4jLevel.toString());
           //check level against jul
           return juli.isLoggable(julLevel);
       } else {
           //check level against log4j
           return log4j.isEnabled(log4jLevel);
       }
   }
   

    /**
     * Log with TRACE level
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void trace(String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.TRACE)) {
            if (forceJuli || log4j == null) {
                try {
                    juli.finest(format(message, args));
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (recordCaller) {
                        LogManager.getLogger(getCallerClassName()).trace(format(message, args));
                    } else {
                        log4j.trace(format(message, args));
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with DEBUG level
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void debug(String message, Object... args) {
        if (isDebugEnabled()) {
            if (forceJuli || log4j == null) {
                try {
                    juli.fine(format(message, args));
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (recordCaller) {
                        LogManager.getLogger(getCallerClassName()).debug(format(message, args));
                    } else {
                        log4j.debug(format(message, args));
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with DEBUG level
     * @param e the exception to log
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void debug(Throwable e, String message, Object... args) {
        if (isDebugEnabled()) {
            if (forceJuli || log4j == null) {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.DEBUG, e, message, args)) {
                        juli.log(Level.CONFIG, format(message, args), e);
                    }
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.DEBUG, e, message, args)) {
                        if (recordCaller) {
                            LogManager.getLogger(getCallerClassName()).debug(format(message, args), e);
                        } else {
                            log4j.debug(format(message, args), e);
                        }
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with INFO level
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void info(String message, Object... args) {
        if(isEnabledFor(org.apache.logging.log4j.Level.INFO)){
            if (forceJuli || log4j == null) {
                try {
                    juli.info(format(message, args));
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (recordCaller) {
                        // TODO: It is expensive to extract caller-info
                        // we should only do it if we know the message is being
                        // logged (level)
                        LogManager.getLogger(getCallerClassName()).info(format(message, args));
                    } else {
                        log4j.info(format(message, args));
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with INFO level
     * @param e the exception to log
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void info(Throwable e, String message, Object... args) {
        if(isEnabledFor(org.apache.logging.log4j.Level.INFO)){
            if (forceJuli || log4j == null) {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.INFO, e, message, args)) {
                        juli.log(Level.INFO, format(message, args), e);
                    }
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.INFO, e, message, args)) {
                        if (recordCaller) {
                            LogManager.getLogger(getCallerClassName()).info(format(message, args), e);
                        } else {
                            log4j.info(format(message, args), e);
                        }
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with WARN level
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void warn(String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.WARN)) {
            if (forceJuli || log4j == null) {
                try {
                    juli.warning(format(message, args));
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (recordCaller) {
                        LogManager.getLogger(getCallerClassName()).warn(format(message, args));
                    } else {
                        log4j.warn(format(message, args));
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with WARN level
     * @param e the exception to log
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void warn(Throwable e, String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.WARN)) {
            if (forceJuli || log4j == null) {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.WARN, e, message, args)) {
                        juli.log(Level.WARNING, format(message, args), e);
                    }
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.WARN, e, message, args)) {
                        if (recordCaller) {
                            LogManager.getLogger(getCallerClassName()).warn(format(message, args), e);
                        } else {
                            log4j.warn(format(message, args), e);
                        }
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with ERROR level
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void error(String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.ERROR)) {
            if (forceJuli || log4j == null) {
                try {
                    juli.severe(format(message, args));
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (recordCaller) {
                        LogManager.getLogger(getCallerClassName()).error(format(message, args));
                    } else {
                        log4j.error(format(message, args));
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with ERROR level
     * @param e the exception to log
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void error(Throwable e, String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.ERROR)) {
            if (forceJuli || log4j == null) {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.ERROR, e, message, args)) {
                        juli.log(Level.SEVERE, format(message, args), e);
                    }
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.ERROR, e, message, args)) {
                        if (recordCaller) {
                            LogManager.getLogger(getCallerClassName()).error(format(message, args), e);
                        } else {
                            log4j.error(format(message, args), e);
                        }
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with FATAL level
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void fatal(String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.FATAL)) {
            if (forceJuli || log4j == null) {
                try {
                    juli.severe(format(message, args));
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (recordCaller) {
                        LogManager.getLogger(getCallerClassName()).fatal(format(message, args));
                    } else {
                        log4j.fatal(format(message, args));
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * Log with FATAL level
     * @param e the exception to log
     * @param message The message pattern
     * @param args Pattern arguments
     */
    public static void fatal(Throwable e, String message, Object... args) {
        if (isEnabledFor(org.apache.logging.log4j.Level.FATAL)) {
            if (forceJuli || log4j == null) {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.FATAL, e, message, args)) {
                        juli.log(Level.SEVERE, format(message, args), e);
                    }
                } catch (Throwable ex) {
                    juli.log(Level.SEVERE, "Oops. Error in Logger !", ex);
                }
            } else {
                try {
                    if (!niceThrowable(org.apache.logging.log4j.Level.FATAL, e, message, args)) {
                        if (recordCaller) {
                            LogManager.getLogger(getCallerClassName()).fatal(format(message, args), e);
                        } else {
                            log4j.fatal(format(message, args), e);
                        }
                    }
                } catch (Throwable ex) {
                    log4j.error("Oops. Error in Logger !", ex);
                }
            }
        }
    }

    /**
     * If e is a PlayException -> a very clean report
     */
    static boolean niceThrowable(org.apache.logging.log4j.Level level, Throwable e, String message, Object... args) {
        if (e instanceof Exception) {

            Throwable toClean = e;
            for (int i = 0; i < 5; i++) {
                // Clean stack trace
                List<StackTraceElement> cleanTrace = new ArrayList<>();
                for (StackTraceElement se : toClean.getStackTrace()) {
                    if (se.getClassName().startsWith("play.server.PlayHandler$NettyInvocation")) {
                        cleanTrace.add(new StackTraceElement("Invocation", "HTTP Request", "Play!", -1));
                        break;
                    }
                    if (se.getClassName().startsWith("play.server.PlayHandler$SslNettyInvocation")) {
                        cleanTrace.add(new StackTraceElement("Invocation", "HTTP Request", "Play!", -1));
                        break;
                    }
                    if (se.getClassName().startsWith("play.jobs.Job") && se.getMethodName().equals("run")) {
                        cleanTrace.add(new StackTraceElement("Invocation", "Job", "Play!", -1));
                        break;
                    }
                    if (se.getClassName().startsWith("play.server.PlayHandler") && se.getMethodName().equals("messageReceived")) {
                        cleanTrace.add(new StackTraceElement("Invocation", "Message Received", "Play!", -1));
                        break;
                    }
                    if (se.getClassName().startsWith("sun.reflect.")) {
                        continue; // not very interesting
                    }
                    if (se.getClassName().startsWith("java.lang.reflect.")) {
                        continue; // not very interesting
                    }
                    if (se.getClassName().startsWith("com.mchange.v2.c3p0.")) {
                        continue; // not very interesting
                    }
                    if (se.getClassName().startsWith("scala.tools.")) {
                        continue; // not very interesting
                    }
                    if (se.getClassName().startsWith("scala.collection.")) {
                        continue; // not very interesting
                    }
                    cleanTrace.add(se);
                }
                toClean.setStackTrace(cleanTrace.toArray(new StackTraceElement[cleanTrace.size()]));
                toClean = toClean.getCause();
                if (toClean == null) {
                    break;
                }
            }

            StringWriter sw = new StringWriter();

            // Better format for Play exceptions
            if (e instanceof PlayException) {
                PlayException playException = (PlayException) e;
                PrintWriter errorOut = new PrintWriter(sw);
                errorOut.println("");
                errorOut.println("");
                errorOut.println("@" + playException.getId());
                errorOut.println(format(message, args));
                errorOut.println("");
                if (playException.isSourceAvailable()) {
                    errorOut.println(playException.getErrorTitle() + " (In " + playException.getSourceFile() + " around line " + playException.getLineNumber() + ")");
                } else {
                    errorOut.println(playException.getErrorTitle());
                }
                errorOut.println(playException.getErrorDescription().replaceAll("</?\\w+/?>", "").replace('\n', ' '));
            } else {
                sw.append(format(message, args));
            }

            try {
                if (forceJuli || log4j == null) {
                    juli.log(toJuliLevel(level.toString()), sw.toString(), e);
                } else if (recordCaller) {
                    LogManager.getLogger(getCallerClassName(5)).log(level, sw.toString(), e);
                } else {
                    log4j.log(level, sw.toString(), e);
                }
            } catch (Exception e1) {
                log4j.error("Oops. Error in Logger !", e1);
            }
            return true;
        }
        return false;
    }

    /**
     * Try to format messages using java Formatter. 
     * Fall back to the plain message if error.
     */
    static String format(String msg, Object... args) {
        try {
            if (args != null && args.length > 0) {
                return String.format(msg, args);
            }
            return msg;
        } catch (Exception e) {
            return msg;
        }
    }

    /**
     * Info about the logger caller
     */
    static class CallInfo {

        public String className;
        public String methodName;

        public CallInfo() {
        }

        public CallInfo(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }

    /**
     * @return the className of the class actually logging the message
     */
    static String getCallerClassName() {
        int level = 5;
        return getCallerClassName(level);
    }

    /**
     * @return the className of the class actually logging the message
     */
    static String getCallerClassName(int level) {
        CallInfo ci = getCallerInformation(level);
        return ci.className;
    }

    /**
     * Examine stack trace to get caller
     * @param level method stack depth
     * @return who called the logger
     */
    static CallInfo getCallerInformation(int level) {
        StackTraceElement[] callStack = Thread.currentThread().getStackTrace();
        StackTraceElement caller = callStack[level];
        return new CallInfo(caller.getClassName(), caller.getMethodName());
    }

    /**
     * class for init date
     */
    static class LoggerInit{
        URL log4jConf ;

        LoggerInit() {
            String log4jPath = Play.configuration.getProperty("application.log.path", "/log4j.xml");
            log4jConf = Logger.class.getResource(log4jPath);
            if (log4jConf == null) { // try again with the .properties
                log4jPath = Play.configuration.getProperty("application.log.path", "/log4j.properties");
                log4jConf = Logger.class.getResource(log4jPath);
            }
        }

        public URL getLog4jConf() {
            return log4jConf;
        }

        public boolean access() throws URISyntaxException {
            return Paths.get(log4jConf.toURI()).startsWith(Play.applicationPath.toPath());
        }
    }
    /**
     * juli handler that Redirect to log4j
     */
    public static class JuliToLog4jHandler extends Handler {

        @Override
        public void publish(LogRecord record) {
            org.apache.logging.log4j.Logger log4j = getTargetLogger(record.getLoggerName());
            final org.apache.logging.log4j.Level level = toLog4j(record.getLevel());
            log4j.log(level, toLog4jMessage(record), record.getThrown());
        }

        static org.apache.logging.log4j.Logger getTargetLogger(String loggerName) {
            return loggerName != null ? LogManager.getLogger(loggerName) : LogManager.getRootLogger();
        }

        public static org.apache.logging.log4j.Logger getTargetLogger(Class<?> clazz) {
            return getTargetLogger(clazz.getName());
        }

        private String toLog4jMessage(LogRecord record) {
            String message = record.getMessage();
            // Format message
            try {
                Object parameters[] = record.getParameters();
                if (parameters != null && parameters.length != 0) {
                    // Check for the first few parameters ?
                    if (message.indexOf("{0}") >= 0
                            || message.indexOf("{1}") >= 0
                            || message.indexOf("{2}") >= 0
                            || message.indexOf("{3}") >= 0) {
                        message = MessageFormat.format(message, parameters);
                    }
                }
            } catch (Exception ex) {
                // ignore Exception
            }
            return message;
        }

        private org.apache.logging.log4j.Level toLog4j(java.util.logging.Level level) {
            if (java.util.logging.Level.SEVERE == level) {
                return org.apache.logging.log4j.Level.ERROR;
            } else if (java.util.logging.Level.WARNING == level) {
                return org.apache.logging.log4j.Level.WARN;
            } else if (java.util.logging.Level.INFO == level) {
                return org.apache.logging.log4j.Level.INFO;
            } else if (java.util.logging.Level.OFF == level) {
                return org.apache.logging.log4j.Level.TRACE;
            }
            return org.apache.logging.log4j.Level.TRACE;
        }

        @Override
        public void flush() {
            // nothing to do
        }

        @Override
        public void close() {
            // nothing to do
        }
    }
}
