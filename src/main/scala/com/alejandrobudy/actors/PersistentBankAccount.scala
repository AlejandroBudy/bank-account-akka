package com.alejandrobudy.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

// A single bank account
class PersistentBankAccount {

  // commands = messages
  sealed trait Command
  case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response])
      extends Command
  case class UpdateBalance(id: String, currency: String, amount: Double, replyTo: ActorRef[Response]) extends Command
  case class GetBankAccount(id: String, replyTo: ActorRef[Response])                                  extends Command
  // events = to persist in cassandra
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Double)               extends Event
  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // responses
  sealed trait Response
  case class BankAccountCreatedResponse(id: String) extends Response
  // If account not exists -> None
  case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
  case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount])            extends Response

  // command handler = message handler => persist event
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        /*
         - bank creates me
         - bank sends me CreateBankAccount
         - I persist BankAccountCreated
         - I update my state
         - reply back to bank with BankAccountCreatedResponse
         - (the bank surfaces the response to the HTTP server)
         */
        Effect
        //Persisted into cassandra
          .persist(BankAccountCreated(BankAccount(id = id, user = user, currency = currency, balance = initialBalance))) //Persisted into cassandra
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))
      // Already handled Id
      case UpdateBalance(_, _, amount, replyTo) =>
        val newBalance = state.balance + amount
        if (newBalance < 0) Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
        else
          Effect
            .persist(BalanceUpdated(newBalance))
            .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))

      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))
  }
  // Event handler => update state
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
      case BalanceUpdated(newBalance)      => state.copy(balance = newBalance)
  }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )

}
