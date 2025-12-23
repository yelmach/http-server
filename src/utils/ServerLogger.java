package utils;

import java.io.File;
import java.io.IOException;
import java.util.logging.*;

public final class ServerLogger {

    private static final Logger logger = Logger.getLogger(ServerLogger.class.getName());

    static {
        try {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);

            // Create logs directory if it doesn't exist
            File logsDir = new File("logs");
            if (!logsDir.exists()) {
                logsDir.mkdir();
            }

            // FileHandler to log everything
            FileHandler fileHandler = new FileHandler("logs/server.log", 10_000_000, 1, true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new CustomFormatter());

            // ConsoleHandler for warnings and errors only
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.WARNING);
            consoleHandler.setFormatter(new SimpleFormatter());

            logger.addHandler(fileHandler);
            logger.addHandler(consoleHandler);

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize logger", e);
        }
    }

    private ServerLogger() {
    }

    public static Logger get() {
        return logger;
    }

    static class CustomFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder();

            // Format: "LEVEL | TIMESTAMP | Message"
            sb.append(String.format("%-7s | %-19s | %s%n",
                    record.getLevel(),
                    formatTimestamp(record.getMillis()),
                    record.getMessage()));

            return sb.toString();
        }

        private String formatTimestamp(long millis) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(millis));
        }
    }
}
