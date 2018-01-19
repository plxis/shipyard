package shipyard.command

public class Arg {
  String name
  String description
  boolean required
  List mutuallyExclusiveArgs

  Arg(String name, String description, boolean required) {
    this(name, description, required, null)
  }

  Arg(String name, String description, boolean required, List mutuallyExclusiveArgs) {
    this.name = name
    this.description = description
    this.required = required
    this.mutuallyExclusiveArgs = mutuallyExclusiveArgs
  }

  public String toString() {
    StringBuffer sb = new StringBuffer()
    sb.append("  -")
    sb.append(name.padRight(15, " "))
    sb.append(description)
    if (required) sb.append(" (Required)")
    if (mutuallyExclusiveArgs) {
      sb.append(" (cannot be used with: ")
      sb.append(mutuallyExclusiveArgs.join(", "))
      sb.append(")")
    }
    return sb.toString()
  }

}