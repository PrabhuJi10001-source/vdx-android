package com.vdx.sonic.executor

import com.vdx.sonic.SonicIntent

/**
 * Hard denylist: VDX must not open banking or payment apps/sites.
 * Fail-closed. Not a confirmation — a refusal. Policy 2026-08-28.
 */
object PaymentBlocklist {

    const val REASON =
        "VDX does not open banking or payment apps. Do that yourself."

    private val PACKAGES = setOf(
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.dreamplug.androidapp",
        "com.stripe.android.dashboard",
        "com.wise.android",
        "com.revolut.revolut",
        "com.chase.sig.android",
        "com.wf.wellsfargomobile",
        "com.infonow.bofa",
        "com.usbank.mobilebanking",
        "com.citi.citimobile",
        "com.konylabs.capitalone",
        "com.sbi.SBIFreedomPlus",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.axis.mobile",
        "ae.emiratesnbd.mobile",
        "com.adcb.bank",
        "com.mashreq.neo",
        "com.fab.mobilebanking",
        "com.payit.ae",
    )

    private val NEEDLES = listOf(
        "paypal", "venmo", "cash app", "cashapp",
        "google pay", "gpay", "g pay", "apple pay", "samsung pay",
        "phonepe", "paytm", "razorpay", "stripe",
        "wise.com", "revolut", "zelle", "wire transfer",
        "bank of america", "wellsfargo", "wells fargo", "chase bank",
        "citibank", "capital one", "us bank",
        "hdfc", "icici", "axis bank", "sbi bank",
        "emirates nbd", "adcb", "mashreq", "fab bank",
        "online banking", "mobile banking", "netbanking",
        "credit card", "debit card", "upi ",
        "checkout.stripe", "pay.google",
    )

    fun blockedPackage(packageName: String?): Boolean {
        val pkg = packageName?.lowercase()?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return pkg in PACKAGES || PACKAGES.any { pkg.startsWith("$it.") }
    }

    fun blockedText(text: String?): Boolean {
        val t = " ${text.orEmpty().lowercase()} "
        if (t.isBlank()) return false
        return NEEDLES.any { needle -> t.contains(needle) }
    }

    fun blocked(intent: SonicIntent): Boolean {
        if (blockedPackage(intent.targetApp)) return true
        val blob = buildString {
            append(intent.rawText)
            append(' ')
            intent.entities.values.forEach { append(it).append(' ') }
            intent.targetApp?.let { append(it) }
        }
        return blockedText(blob)
    }
}
