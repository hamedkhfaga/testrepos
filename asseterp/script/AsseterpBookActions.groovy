/*
 * Asset ERP — screen actions for Depreciation Books.
 * Each method is invoked by the corresponding screen via:
 *   <script location="component://asseterp/script/AsseterpBookActions.groovy" invoke="..."/>
 */

import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator

/**
 * FindAssetBooks — called from the FindAssetBooks screen.
 * Searches AsseterpAssetBook by optional fixedAssetId and/or bookTypeId.
 * Populates context.assetBookList.
 */
def findAssetBooks() {
    def conditions = []

    String fixedAssetId = parameters.fixedAssetId
    if (fixedAssetId) {
        conditions << EntityCondition.makeCondition("fixedAssetId", EntityOperator.LIKE, "%${fixedAssetId}%")
    }

    String bookTypeId = parameters.bookTypeId
    if (bookTypeId) {
        conditions << EntityCondition.makeCondition("bookTypeId", EntityOperator.EQUALS, bookTypeId)
    }

    def findCondition = conditions
            ? EntityCondition.makeCondition(conditions, EntityOperator.AND)
            : null

    def books = findCondition
            ? from("AsseterpAssetBook").where(findCondition).orderBy("fixedAssetId", "bookTypeId").queryList()
            : from("AsseterpAssetBook").orderBy("fixedAssetId", "bookTypeId").queryList()

    context.assetBookList = books
}
