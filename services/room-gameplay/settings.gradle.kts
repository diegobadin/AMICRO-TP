rootProject.name = "room-gameplay"

// The rules live in their own module with no framework on its classpath (plan D1). That is what
// lets the property and replay suites run in milliseconds without a database or a container, and
// what keeps an argument about the rules from turning into an argument about infrastructure.
include("engine")
