/*
 * ui plugin — server-side workflow guards (OFBiz core untouched).
 * (Moved from qrcore 2026-07-24.)
 *
 * uiPaymentVoidGuard: attached by ui secas.xml to the INVOKE stage of
 * setPaymentStatus and voidPayment. Members of security group UI_PMT_NOVOID
 * are denied Cancel/Void transitions — all other payment actions untouched.
 * Stock OFBiz cannot express this (Cancel/Void share ACCOUNTING_UPDATE with
 * every other payment action), hence this plugin-level deny-list guard.
 */
package org.apache.ofbiz.ui

Map uiPaymentVoidGuard() {
    // voidPayment carries no statusId parameter — treat it as PMNT_VOID
    String target = parameters.statusId ?: 'PMNT_VOID'
    if (['PMNT_CANCELLED', 'PMNT_VOID'].contains(target)) {
        String uid = userLogin?.userLoginId
        if (uid) {
            def restricted = from('UserLoginSecurityGroup')
                    .where('userLoginId', uid, 'groupId', 'UI_PMT_NOVOID')
                    .filterByDate()
                    .queryList()
            if (restricted) {
                return error("User ${uid} is not authorized to cancel or void payments " +
                        '(UI_PMT_NOVOID restriction). Contact an administrator.')
            }
        }
    }
    return success()
}
