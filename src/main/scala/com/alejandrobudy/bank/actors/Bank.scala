package com.alejandrobudy.bank.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import com.alejandrobudy.bank.actors.PersistentBankAccount.{Command, Response}
import com.alejandrobudy.bank.actors.PersistentBankAccount.Command.CreateBankAccount

import java.util.UUID



object Bank {

  // commands = messages
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._
  // events
  sealed trait Event
  case class BankAccountCreated(id: String) extends Event
  // state
  case class State(accounts: Map[String, ActorRef[Command]])

  //command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] =
    (state, command) =>
      command match {
        case createCommand @ CreateBankAccount(_, _, _, _) =>
          val id             = UUID.randomUUID().toString
          val newBankAccount = context.spawn(PersistentBankAccount(id), id)
          Effect
            .persist(BankAccountCreated(id))
            .thenReply(newBankAccount)(_ => createCommand)
        case updateCommand @ UpdateBalance(id, _, _, replyTo) =>
          state.accounts.get(id) match {
            case Some(account) => Effect.reply(account)(updateCommand)
            case None          => Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
          }
        case getCommand @ GetBankAccount(id, replyTo) =>
          state.accounts.get(id) match {
            case Some(value) => Effect.reply(value)(getCommand)
            case None        => Effect.reply(replyTo)(GetBankAccountResponse(None))
          }
    }
  //event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State =
    (state, event) =>
      event match {
        case BankAccountCreated(id) =>
          val account =
            context
              .child(id) // Exists after command handler
              .getOrElse(context.spawn(PersistentBankAccount(id), id)) //Recovery mode does not exist hence it has to be created
              .asInstanceOf[ActorRef[Command]]
          state.copy(state.accounts + (id -> account))
    }
  // behavior
  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("bank"),
        emptyState = State(Map()),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    }

  }
}

object BankPlayground {

  def main(args: Array[String]): Unit = {
    val rootBehavior : Behavior[NotUsed] = Behaviors.setup {
      context =>
        val bank = context.spawn(Bank(), "bank")

        //Ask pattern
        import akka.actor.typed.scaladsl.AskPattern._
        import scala.concurrent.duration._
        implicit val timeOut : Timeout = Timeout(2.seconds)
        implicit val scheduler: Scheduler = context.system.scheduler

        bank.ask(replyTo => CreateBankAccount("alex", "EUR", 10, replyTo)).flatMap {
          case Response.BankAccountCreatedResponse(id) => ???
          case Response.BankAccountBalanceUpdatedResponse(maybeBankAccount) => ???
          case Response.GetBankAccountResponse(maybeBankAccount) => ???
        }
    }
  }
}
