[![Build Status](https://travis-ci.org/SumoLogic/sumobot.svg?branch=master)](https://travis-ci.org/SumoLogic/sumobot) [![codecov.io](http://codecov.io/github/SumoLogic/sumobot/coverage.svg?branch=master)](http://codecov.io/github/SumoLogic/sumobot?branch=master) [![Stories in Ready](https://badge.waffle.io/SumoLogic/sumobot.svg?label=ready&title=Ready)](http://waffle.io/SumoLogic/sumobot)

# Sumo Bot

Very early work on a Slack ChatOps bot, written in Akka/Scala. 

## Installation

### Building from source

Publish to local maven repository: 

```
$ mvn clean install
```

### Via Maven 

Add the following snippet to your `pom.xml`: 

```xml
<dependency>
  <groupId>com.sumologic.sumobot</groupId>
  <artifactId>sumobot</artifactId>
  <version>0.1.0</version>
</dependency>    
```

## Configuration and Customization

Sumobot includes a "batteries included" `Main` method that loads a standard set of plugins. In order to add your own plugins and customize which brain is used, you need to write a custom `Main` class. 

### Custom Main Class

Start by implementing your own `Main` method, following this template: 

```scala
object Main extends App {
  Bootstrap.bootstrap(Props(classOf[InMemoryBrain]), DefaultPlugins)
}
```

Adjust the brain and list of plugins (via `PluginCollection`) according to your needs. 

##### Plugin Collections

In order to customize which plugins get loaded, implement the `PluginCollection` interface and add your desired plugins using the `addPlugin()` method. Here's a simple example: 

```scala
object DefaultPlugins extends PluginCollection {
  def setup(implicit system: ActorSystem): Unit = {
    addPlugin("help", Props(classOf[Help]))
    addPlugin("conversations", Props(classOf[Conversations]))
    addPlugin("beer", Props(classOf[Beer]))
  }
}
```

##### Brains

Sumobot plugins can use a brain to remember state. There are currently two implementations of brains: 

* `S3Brain` uses AWS S3 to store brain contents persistently. It requires you to specify AWS credentials, the name of the key to use, as well as an S3 bucket. 
* `InMemoryBrain` is useful for testing, but loses all state when the JVM exits.

### Plugin Configuration 

TODO: How to configure the darn thing. 

## Writing Plugins

Every plugin is an Akka actor and receives all Slack messages seen by sumobot as `IncomingMessage`. Along with the text of the message, `IncomingMessage` contains a few other fields like the channel, and whether the message is addressed to the bot directly. All of this information is used to match `IncomingMessage` and respond if appropriate. 

Here is a minimal exmaple of a plugin implementation: 

```scala
class HelloWorld extends BotPlugin {

  override protected def help: String =
    s"""
       |I'll say hello to anything:
       |
       |hello <something> - I'll just say hello.
    """.stripMargin

  private val Hello = matchText("hello (\\w+)")

  override protected def receiveIncomingMessage: ReceiveIncomingMessage = {
    case message@IncomingMessage(Hello(who), true, _, _) =>
      message.respond(s"Hello $who")
    }
  }
}
``` 


### Interaction Style

Here are a few recommendations for designing interactions with the bot: 

* Keep it light and fun.
* Design your regular expressions to be fairly permissive, i.e. make it easy for people to "get it right".
* Write your help text in the first person. 
* Use `Emotions` where applicable. 

### License

Released under Apache 2.0 License.