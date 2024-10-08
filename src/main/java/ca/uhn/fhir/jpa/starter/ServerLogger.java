package ca.uhn.fhir.jpa.starter;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * @copied from
 *         https://github.com/carin-alliance/cpcds-server-ri/blob/patient-access/src/main/java/ca/uhn/fhir/jpa/starter/authorization/RegisterEndpoint.java
 */
public class ServerLogger {

  // Instance of a logger. File copied from cpcds-server-ri
  private static ServerLogger singletonServerLogger;

  private Logger logger;
  private static String LOGFILE = "server.log";

  private ServerLogger() {
    this.logger = Logger.getLogger("Server");
    try {
      createLogFileIfNotExists();
      FileHandler fh = new FileHandler(LOGFILE);
      SimpleFormatter formatter = new SimpleFormatter();
      fh.setFormatter(formatter);

      this.setLevel(Level.FINEST);

      this.logger.addHandler(fh);

    } catch (SecurityException e) {
      this.logger.log(Level.SEVERE,
          "ServerLogger::ServerLogger:SecurityException(SecurityException creating file handler. Logging will not go to file)",
          e);
    } catch (IOException e) {
      this.logger.log(Level.SEVERE, "ServerLogger::ServerLogger:IOException", e);
    }
  }

  /**
   * Get the logger
   * 
   * @return the logger
   */
  public static Logger getLogger() {
    if (singletonServerLogger == null)
      singletonServerLogger = new ServerLogger();
    return singletonServerLogger.logger;
  }

  /**
   * Get the path of the log file
   * 
   * @return the path of the log file
   */
  public static String getLogPath() {
    return LOGFILE;
  }

  /**
   * Sets the level of logging to the logger and all handlers
   * 
   * @param level the new level
   */
  private void setLevel(Level level) {
    this.logger.info("ServerLogger::Setting logger level to " + level.getName());
    this.logger.setLevel(level);
    for (Handler handler : this.logger.getHandlers()) {
      handler.setLevel(level);
    }
  }

  private static void createLogFileIfNotExists() throws IOException {
    File logFile = new File(LOGFILE);
    logFile.createNewFile();
  }
}
