package shipyard

import shipyard.command.*
import util.*

import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Command line wrapper for the superintedent program. 
 */
public class Main {
  private static Log log = Log.getLogger(Main)

  public static void main(String[] args) {
    def exitCode = 2
    def props
    def output

    try {
      props = loadProperties(args)

      if (!props.command || !(props.command.capitalize() in Command.list().collect { it.simpleName} )) usage()

      configureLogging(props)

      // Instantiate a new agent and initialize it with the specified prop file.
      def agent = new SuperintendentAgent()
      agent.init(props)
     
      // Write the PID to the PID file
      createPIDFile(props.pidFile)

      exitCode = agent.call() 
      output = agent.output
      if (exitCode == 0 && !output) {
        output = "okay" 
      }
      log.info("Received response",[exitCode:exitCode,outputSize:output?.size()])
    } catch (groovy.json.JsonException je) {
      log.error("JSON error; see console for details")
      output = "JSON Error: " + je.message
    } catch (Exception e) {
      log.error("Unexpected problem during agent call",[reason:e], e)
      output = "Error: " + e.message
    } finally {
      // Remove the PID file if it hasn't already been removed by an
      // external process requesting us to shutdown.
      if (props?.pidFile) deletePIDFile(props.pidFile)
    }      
    if (output) println output

    terminate(exitCode)
  }

  public static usage() {
    StringBuffer sb = new StringBuffer()
    sb.append("usage: superintendent <command> [args] [arg-files]\n")
    sb.append("\nAvailable commands\n")
    Command.list().each {
      def name = it.simpleName[0].toLowerCase() + it.simpleName[1..-1] 
      sb.append("  ${name.padRight(20, ' ')} ${it.newInstance().description}\n") 
    }

    sb.append("\nWhere args can be either command-specific arguments or -help to list valid args for the command\n")
    sb.append("Optionally, specify one or more filename args and all key=value pairs in the files will be converted into arguments.\n")
    println sb.toString()
    terminate(1)
  }

  public static void terminate(exitCode) {
    System.exit(exitCode)
  }

  public static Properties loadPropertiesFile(propFilename) {
    log.debug("Loading properties",[propFile:propFilename])
    def props = new Properties()
    ResourceLoader.withResourceStream(propFilename) { stream ->
      props.load(stream)
    }
    return props
  }

  public static Properties loadProperties(String[] args) {
    def stdArgs = []
    def override = [:]
    def command
    args.each {
      if (it.startsWith("-")) {
        def arg = it[1..-1]
        if (it.contains("=")) {
          def pair = arg.tokenize("=")
          override[pair[0]] = pair[1]
        } else {
          override[arg] = "true"
        }
      } else if (!command) {
        command = it
      }
      else {
        stdArgs << it
      }
    }

    if (!stdArgs) {
      stdArgs << "superintendent.properties"
      stdArgs << "superintendent-secure.properties"
    }

    def props = new Properties()
    if (command) props.command = command
    // Load the properties from the properties file(s).
    stdArgs.each {
      props?.putAll(loadPropertiesFile(it))
    }

    // Ensure a properties file was successfully loaded
    if (!props) {
      System.err << "Aborting, either the default superintendent.properties file or a custom specified properties file must be accessible.\n"
      System.exit(2)
    }

    // Apply the overrides
    props.putAll(override)

    // Convert to system properties
    convertToSystemProperties(props)

    return props
  }

  /**
   * Convert any properties prefix with 'system.' into system properties (stripping the prefix)
   */
  protected static void convertToSystemProperties(Properties props) {
    def convertedList = []
    props.each { k, v ->
      if(k.toUpperCase().startsWith("SYSTEM.")) {
        def propName =  k - ~/(?i)^system\./
        log.debug("Converting property to system property",[k:propName])
        System.setProperty(propName, v)
        convertedList << k
      }
    }

    convertedList.each { props.remove(it) }
  }

  /**
   * Returns true if the PID file has been deleted.
   */
  protected static boolean shouldShutdown(String filename) {
    return !(new File(filename).exists())
  }

  /**
   * Deletes the specified PID file.
   */
  protected static boolean deletePIDFile(String filename) {
    File f = new File(filename)
    if (f.exists()) {
      f.delete()
    }
  }

  /**
   * Creates the specified PID file containing the process ID.
   */
  protected static void createPIDFile(String filename) {
    new File(filename).text = getPID() + "\n"
  }

  /**
   * Returns the PID of the running program.
   */
  protected static def getPID() {
    def name = ManagementFactory.getRuntimeMXBean().name
    return name?.substring(0, name?.indexOf("@"))
  }

  /**
   * Returns this product's or tool's descriptive title.
   */
  public static String getTitle() {
    return Shipyard.genProductVersionString("Shipyard")
  }

  public static void configureLogging(Properties props) {
    log.info(getTitle())

    if (props.logLevel) {
      Log.setRootLevel(props.logLevel)
    }
  }
}
