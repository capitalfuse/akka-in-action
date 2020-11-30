package aia.deploy

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.actor.ActorSystem
import org.scalatest.wordspec.AnyWordSpecLike

class HelloWorldTest extends TestKit(ActorSystem("HelloWorldTest"))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  val actor = TestActorRef[HelloWorld]

  override def afterAll(): Unit = {
    system.terminate()
  }
  "HelloWorld" must {
    "reply when sending a string" in {
      actor ! "everybody"
      expectMsg("Hello everybody")
    }
  }
}
