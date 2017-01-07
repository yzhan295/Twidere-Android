package org.mariotaku.twidere.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.anjlab.android.iab.v3.BillingProcessor
import com.anjlab.android.iab.v3.Constants.*
import com.anjlab.android.iab.v3.SkuDetails
import com.anjlab.android.iab.v3.TransactionDetails
import nl.komponents.kovenant.task
import nl.komponents.kovenant.ui.alwaysUi
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.mariotaku.twidere.Constants
import org.mariotaku.twidere.constant.EXTRA_CURRENCY
import org.mariotaku.twidere.constant.EXTRA_PRICE
import org.mariotaku.twidere.constant.IntentConstants.INTENT_PACKAGE_PREFIX
import org.mariotaku.twidere.constant.RESULT_NOT_PURCHASED
import org.mariotaku.twidere.constant.RESULT_SERVICE_UNAVAILABLE
import org.mariotaku.twidere.fragment.ProgressDialogFragment
import java.lang.ref.WeakReference

/**
 * Created by mariotaku on 2016/12/25.
 */

class GooglePlayInAppPurchaseActivity : BaseActivity(), BillingProcessor.IBillingHandler {

    private lateinit var billingProcessor: BillingProcessor

    private val productId: String get() = intent.getStringExtra(EXTRA_PRODUCT_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billingProcessor = BillingProcessor(this, Constants.GOOGLE_PLAY_LICENCING_PUBKEY, this)
        if (!isFinishing && !BillingProcessor.isIabServiceAvailable(this)) {
            handleError(BILLING_RESPONSE_RESULT_USER_CANCELED)
        }
    }

    override fun onDestroy() {
        billingProcessor.release()
        super.onDestroy()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!billingProcessor.handleActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // MARK: Payment methods
    override fun onBillingError(code: Int, error: Throwable?) {
        handleError(code)
    }

    override fun onBillingInitialized() {
        // See https://github.com/anjlab/android-inapp-billing-v3/issues/156
        if (intent.action == ACTION_RESTORE_PURCHASE) {
            performRestorePurchase()
        } else {
            billingProcessor.purchase(this, productId)
        }
    }

    override fun onProductPurchased(productId: String?, details: TransactionDetails?) {
        getProductDetailsAndFinish()
    }

    override fun onPurchaseHistoryRestored() {
        getProductDetailsAndFinish()
    }

    private fun handleError(billingResponse: Int) {
        when (billingResponse) {
            BILLING_ERROR_OTHER_ERROR, BILLING_ERROR_INVALID_DEVELOPER_PAYLOAD -> {
                getProductDetailsAndFinish()
            }
            else -> {
                setResult(getResultCode(billingResponse))
                finish()
            }
        }
    }

    private fun handlePurchased(sku: SkuDetails, transaction: TransactionDetails) {
        val data = Intent().putExtra(EXTRA_PRICE, sku.priceValue).putExtra(EXTRA_CURRENCY, sku.currency)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun performRestorePurchase() {
        executeAfterFragmentResumed {
            val weakThis = WeakReference(it as GooglePlayInAppPurchaseActivity)
            val dfRef = WeakReference(ProgressDialogFragment.show(it.supportFragmentManager, "consume_purchase_progress"))
            task {
                val activity = weakThis.get() ?: throw PurchaseException(BILLING_RESPONSE_RESULT_USER_CANCELED)
                val productId = activity.productId
                val bp = activity.billingProcessor
                bp.loadOwnedPurchasesFromGoogle()
                val skuDetails = bp.getPurchaseListingDetails(productId)
                        ?: throw PurchaseException(BILLING_RESPONSE_RESULT_ERROR)
                val transactionDetails = bp.getPurchaseTransactionDetails(productId)
                        ?: throw PurchaseException(BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED)
                return@task Pair(skuDetails, transactionDetails)
            }.successUi { result ->
                weakThis.get()?.handlePurchased(result.first, result.second)
            }.failUi { error ->
                if (error is PurchaseException) {
                    weakThis.get()?.handleError(error.code)
                } else {
                    weakThis.get()?.handleError(BILLING_RESPONSE_RESULT_ERROR)
                }
            }.alwaysUi {
                weakThis.get()?.executeAfterFragmentResumed {
                    val fm = weakThis.get()?.supportFragmentManager
                    val df = dfRef.get() ?: (fm?.findFragmentByTag("consume_purchase_progress") as? DialogFragment)
                    df?.dismiss()
                }
            }
        }
    }

    private fun getProductDetailsAndFinish() {
        executeAfterFragmentResumed {
            val weakThis = WeakReference(it as GooglePlayInAppPurchaseActivity)
            val dfRef = WeakReference(ProgressDialogFragment.show(it.supportFragmentManager, "consume_purchase_progress"))
            task {
                val activity = weakThis.get() ?: throw PurchaseException(BILLING_RESPONSE_RESULT_USER_CANCELED)
                val bp = activity.billingProcessor
                bp.loadOwnedPurchasesFromGoogle()
                val skuDetails = bp.getPurchaseListingDetails(productId)
                        ?: throw PurchaseException(BILLING_RESPONSE_RESULT_ERROR)
                val transactionDetails = bp.getPurchaseTransactionDetails(productId)
                        ?: throw PurchaseException(BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED)
                return@task Pair(skuDetails, transactionDetails)
            }.successUi { result ->
                weakThis.get()?.handlePurchased(result.first, result.second)
            }.failUi { error ->
                if (error is PurchaseException) {
                    weakThis.get()?.handleError(error.code)
                } else {
                    weakThis.get()?.handleError(BILLING_RESPONSE_RESULT_ERROR)
                }
            }.alwaysUi {
                weakThis.get()?.executeAfterFragmentResumed {
                    val fm = weakThis.get()?.supportFragmentManager
                    val df = dfRef.get() ?: (fm?.findFragmentByTag("consume_purchase_progress") as? DialogFragment)
                    df?.dismiss()
                }
            }
        }

    }

    private fun getResultCode(billingResponse: Int): Int {
        val resultCode = when (billingResponse) {
            BILLING_RESPONSE_RESULT_OK -> Activity.RESULT_OK
            BILLING_RESPONSE_RESULT_USER_CANCELED -> Activity.RESULT_CANCELED
            BILLING_RESPONSE_RESULT_SERVICE_UNAVAILABLE -> RESULT_SERVICE_UNAVAILABLE
            BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED -> RESULT_NOT_PURCHASED
            BILLING_RESPONSE_RESULT_ERROR -> RESULT_NOT_PURCHASED
            else -> billingResponse
        }
        return resultCode
    }

    class PurchaseException(val code: Int) : Exception()

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
        const val ACTION_RESTORE_PURCHASE = "${INTENT_PACKAGE_PREFIX}RESTORE_PURCHASE"
    }
}
