from __future__ import annotations

import sys
import unittest
import os
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
    env_path = Path(__file__).resolve().parents[2] / ".env"
    if env_path.is_file():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            clean_line = line.strip()
            if not clean_line or clean_line.startswith("#") or "=" not in clean_line:
                continue
            key, value = clean_line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))

if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
    raise unittest.SkipTest("HUIXIAO_DATABASE_URL is required; local databases are not supported.")

from fastapi import HTTPException

from app.services import apple_iap


def fake_jws(payload: dict) -> str:
    return f"{apple_iap._b64_json({'alg': 'ES256'})}.{apple_iap._b64_json(payload)}.signature"


class AppleIapTest(unittest.TestCase):
    def test_verify_transaction_accepts_matching_apple_payload(self) -> None:
        signed = fake_jws(
            {
                "transactionId": "1000001",
                "originalTransactionId": "1000000",
                "productId": "com.kunqiong.huiyi.plan.basic",
                "bundleId": "com.huiyi.app.ios",
                "environment": "Sandbox",
                "purchaseDate": 1783000000000,
            }
        )

        with patch.object(apple_iap, "require_iap_config"), patch.object(
            apple_iap, "_fetch_transaction_info", return_value={"signedTransactionInfo": signed}
        ):
            result = apple_iap.verify_transaction("1000001", "com.kunqiong.huiyi.plan.basic", signed)

        self.assertEqual(result["transaction_id"], "1000001")
        self.assertEqual(result["product_id"], "com.kunqiong.huiyi.plan.basic")
        self.assertEqual(result["original_transaction_id"], "1000000")
        self.assertEqual(result["purchase_date_ms"], 1783000000000)

    def test_verify_transaction_rejects_wrong_product(self) -> None:
        signed = fake_jws(
            {
                "transactionId": "1000001",
                "productId": "com.kunqiong.huiyi.plan.plus",
                "bundleId": "com.huiyi.app.ios",
            }
        )

        with patch.object(apple_iap, "require_iap_config"), patch.object(
            apple_iap, "_fetch_transaction_info", return_value={"signedTransactionInfo": signed}
        ):
            with self.assertRaises(HTTPException) as ctx:
                apple_iap.verify_transaction("1000001", "com.kunqiong.huiyi.plan.basic", signed)

        self.assertEqual(ctx.exception.status_code, 422)
        self.assertIn("商品不匹配", str(ctx.exception.detail))

    def test_verify_transaction_rejects_wrong_bundle(self) -> None:
        signed = fake_jws(
            {
                "transactionId": "1000001",
                "productId": "com.kunqiong.huiyi.plan.basic",
                "bundleId": "com.other.app",
            }
        )

        with patch.object(apple_iap, "require_iap_config"), patch.object(
            apple_iap, "_fetch_transaction_info", return_value={"signedTransactionInfo": signed}
        ):
            with self.assertRaises(HTTPException) as ctx:
                apple_iap.verify_transaction("1000001", "com.kunqiong.huiyi.plan.basic", signed)

        self.assertEqual(ctx.exception.status_code, 422)
        self.assertIn("Bundle ID", str(ctx.exception.detail))


if __name__ == "__main__":
    unittest.main()
