package org.kudndre.tinvest.reporter

import ru.tinkoff.invest.openapi.OpenApi
import ru.tinkoff.invest.openapi.models.operations.Operation
import ru.tinkoff.invest.openapi.models.operations.OperationStatus
import ru.tinkoff.invest.openapi.models.operations.OperationType
import ru.tinkoff.invest.openapi.models.user.BrokerAccountType
import ru.tinkoff.invest.openapi.okhttp.OkHttpOpenApiFactory
import java.math.BigDecimal
import java.math.BigDecimal.ZERO
import java.math.BigDecimal.valueOf
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.ChronoUnit.MONTHS
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

val logger: Logger = Logger.getLogger("custom")
val apiLogger: Logger = Logger.getLogger("apiLogger").apply { }


fun main(args: Array<String>) {
    val token = args[0]
    apiLogger.level = Level.WARNING

    processOperations(getOperations(token))
    exitProcess(0)
}

private fun getOperations(token: String): MutableList<Operation> {
    OkHttpOpenApiFactory(token, apiLogger).createOpenApiClient { }.use {
        val brokerAccount = brokerAccount(it)

        return it.operationsContext.getOperations(
                OffsetDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                null, brokerAccount).get().operations
    }
}

private fun brokerAccount(it: OpenApi) =
        it.userContext.accounts.get()
                .accounts
                .first { it.brokerAccountType == BrokerAccountType.Tinkoff }
                .brokerAccountId

private fun processOperations(operations: List<Operation>) {
    operations
            .filter { it.status == OperationStatus.Done }
            .filter { it.figi != null }
            .groupBy {
                it.figi
            }
            .filter { closedFigi(it) }
            .forEach {
                logger.info {
                    """FIGI: ${it.key}, ${it.value.size} operations,
 income: ${incomeAmount(it.value)} ${currency(it)}
 invested: ${investedAmount(it.value)} ${currency(it)}
 income(%): ${incomePercents(it)} %
 period: ${daysPeriod(it)} days
 income per year(%): ${incomePerYear(it)} %
 """
                }
            }
}

private fun incomePerYear(it: Map.Entry<String?, List<Operation>>) =
        String.format("%.2f", incomePercents(it).toDouble() * 365 / daysPeriod(it))

private fun incomePercents(it: Map.Entry<String?, List<Operation>>) =
        incomeAmount(it.value) * valueOf(100) / investedAmount(it.value)

private fun daysPeriod(it: Map.Entry<String?, List<Operation>>) =
        DAYS.between(it.value.map { it.date }.minBy { it }, it.value.map { it.date }.maxBy { it })

private fun closedFigi(it: Map.Entry<String?, List<Operation>>) =
        buyQuantity(it) == sellQuantity(it)

private fun sellQuantity(figiOperations: Map.Entry<String?, List<Operation>>) =
        figiOperations.value.filter { it.operationType == OperationType.Sell }.sumBy { it.quantity ?: 0 }

private fun buyQuantity(figiOperations: Map.Entry<String?, List<Operation>>) =
        figiOperations.value.filter { it.operationType == OperationType.Buy }.sumBy { it.quantity ?: 0 }

private fun currency(it: Map.Entry<String?, List<Operation>>) =
        it.value.map { it.currency.name }.first()

fun incomeAmount(operations: List<Operation>): BigDecimal {
    return operations
            .map { it.payment + (it.commission?.value ?: ZERO) }
            .fold(ZERO) { acc, e -> acc + e }
}

fun investedAmount(operations: List<Operation>): BigDecimal {
    return operations
            .filter { it.operationType == OperationType.Buy }
            .map { -it.payment }
            .fold(ZERO) { acc, e -> acc + e }
}


