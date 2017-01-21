# Akka Typed process demo

Demonstration of the Jan 2017 status of the Akka Typed process DSL used in my blog article on [Composing Actor Behaviors](https://github.com/rkuhn/blog/blob/master/02_composing_actor_behavior.md).

The `/lib` folder contains an assembly of modified Akka jars with the state of [PR 22087](https://github.com/akka/akka/pull/22087) at the time of this writing. You may want to use `/support/akka-typed-experimental_2.12-sources.jar` as a source attachment within your IDE.

Please make sure to select a 2.12.x version of Scala in your IDE, otherwise you will experience binary incompatibilities.

Running the code from the command line is as simple as saying `sbt run`.
