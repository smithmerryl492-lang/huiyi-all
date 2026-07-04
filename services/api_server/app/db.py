from collections.abc import Iterator
from contextlib import contextmanager
import time

from sqlalchemy import BigInteger, Boolean, Column, Float, Index, Integer, MetaData, String, Table, Text, create_engine, inspect, text
from sqlalchemy.engine import Connection
from sqlalchemy.exc import OperationalError

from app.core.config import settings


metadata = MetaData()
ID = String(64)
SHORT = String(64)
NAME = String(255)
TIMESTAMP = String(64)
MANUAL_MIGRATION_TABLE_NAMES = {"apple_transactions"}

users_table = Table(
    "users",
    metadata,
    Column("id", ID, primary_key=True),
    Column("username", SHORT, nullable=False, unique=True),
    Column("phone_e164", SHORT),
    Column("display_name", NAME, nullable=False),
    Column("password_hash", String(255)),
    Column("password_set_at", TIMESTAMP),
    Column("password_updated_at", TIMESTAMP),
    Column("password_failed_attempts", Integer, nullable=False, default=0),
    Column("password_locked_until", TIMESTAMP),
    Column("phone_verified_at", TIMESTAMP),
    Column("last_login_at", TIMESTAMP),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

sms_verification_codes_table = Table(
    "sms_verification_codes",
    metadata,
    Column("id", ID, primary_key=True),
    Column("phone_e164", SHORT, nullable=False),
    Column("scene", SHORT, nullable=False),
    Column("code_hash", String(128), nullable=False),
    Column("expires_at", TIMESTAMP, nullable=False),
    Column("consumed_at", TIMESTAMP),
    Column("attempts", Integer, nullable=False, default=0),
    Column("request_ip", SHORT),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

files_table = Table(
    "files",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False, default="user-336496"),
    Column("original_name", NAME, nullable=False),
    Column("stored_path", Text, nullable=False),
    Column("content_type", String(128), nullable=False),
    Column("size_bytes", BigInteger, nullable=False),
    Column("created_at", TIMESTAMP, nullable=False),
)

tasks_table = Table(
    "tasks",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False, default="user-336496"),
    Column("file_id", ID, nullable=False),
    Column("client_task_id", ID),
    Column("title", NAME, nullable=False),
    Column("source", SHORT, nullable=False),
    Column("status", SHORT, nullable=False),
    Column("error_message", Text),
    Column("progress_percent", Float, nullable=False, default=0.0),
    Column("progress_label", Text),
    Column("progress_stage", SHORT),
    Column("sync_scope", SHORT, nullable=False, default="cloud"),
    Column("knowledge_scope", SHORT, nullable=False, default="cloud"),
    Column("is_private", Boolean, nullable=False, default=False),
    Column("device_id", NAME),
    Column("confirmed", Boolean, nullable=False, default=False),
    Column("created_at_millis", BigInteger),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

results_table = Table(
    "results",
    metadata,
    Column("task_id", ID, primary_key=True),
    Column("source_file_path", Text, nullable=False),
    Column("participants", Text),
    Column("tags_json", Text, nullable=False, default="[]"),
    Column("summary", Text, nullable=False),
    Column("topics_json", Text, nullable=False, default="[]"),
    Column("decisions_json", Text, nullable=False),
    Column("todos_json", Text, nullable=False),
    Column("risks_json", Text, nullable=False, default="[]"),
    Column("transcripts_json", Text, nullable=False),
    Column("generated_at", TIMESTAMP, nullable=False),
)

scheduled_meetings_table = Table(
    "scheduled_meetings",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False),
    Column("time", SHORT, nullable=False),
    Column("title", NAME, nullable=False),
    Column("participants", Text, nullable=False),
    Column("note", Text, nullable=True),
    Column("duration_label", SHORT, nullable=False),
    Column("reminder_label", SHORT, nullable=False),
    Column("start_at_millis", BigInteger),
    Column("end_at_millis", BigInteger),
    Column("created_at_millis", BigInteger, nullable=False),
    Column("status", SHORT, nullable=False),
    Column("calendar_event_id", BigInteger),
    Column("updated_at", TIMESTAMP, nullable=False),
)

knowledge_chunks_table = Table(
    "knowledge_chunks",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False, default="user-336496"),
    Column("task_id", ID, nullable=False),
    Column("chunk_type", SHORT, nullable=False),
    Column("title", NAME, nullable=False),
    Column("text", Text, nullable=False),
    Column("speaker", SHORT),
    Column("timestamp", SHORT),
    Column("start_ms", BigInteger),
    Column("end_ms", BigInteger),
    Column("knowledge_scope", SHORT, nullable=False, default="cloud"),
    Column("is_private", Boolean, nullable=False, default=False),
    Column("embedding_json", Text, nullable=False),
    Column("created_at", TIMESTAMP, nullable=False),
)

speaker_profiles_table = Table(
    "speaker_profiles",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False),
    Column("display_name", NAME, nullable=False),
    Column("centroid_json", Text, nullable=False),
    Column("sample_count", Integer, nullable=False, default=0),
    Column("active", Boolean, nullable=False, default=True),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

speaker_profile_samples_table = Table(
    "speaker_profile_samples",
    metadata,
    Column("id", ID, primary_key=True),
    Column("profile_id", ID, nullable=False),
    Column("user_id", ID, nullable=False),
    Column("task_id", ID),
    Column("speaker_key", NAME),
    Column("speaker_name", NAME),
    Column("embedding_json", Text, nullable=False),
    Column("quality", Float, nullable=False, default=0.0),
    Column("duration_ms", BigInteger),
    Column("created_at", TIMESTAMP, nullable=False),
)

meeting_speaker_embeddings_table = Table(
    "meeting_speaker_embeddings",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False),
    Column("task_id", ID, nullable=False),
    Column("speaker_key", NAME, nullable=False),
    Column("speaker_name", NAME, nullable=False),
    Column("embedding_json", Text, nullable=False),
    Column("quality", Float, nullable=False, default=0.0),
    Column("segment_count", Integer, nullable=False, default=0),
    Column("duration_ms", BigInteger),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

admin_users_table = Table(
    "admin_users",
    metadata,
    Column("id", ID, primary_key=True),
    Column("username", SHORT, nullable=False, unique=True),
    Column("display_name", NAME, nullable=False),
    Column("password_hash", String(255), nullable=False),
    Column("active", Boolean, nullable=False, default=True),
    Column("last_login_at", TIMESTAMP),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

membership_plans_table = Table(
    "membership_plans",
    metadata,
    Column("id", ID, primary_key=True),
    Column("name", NAME, nullable=False),
    Column("price_cents", Integer, nullable=False, default=0),
    Column("transcription_minutes_monthly", Integer, nullable=False, default=0),
    Column("knowledge_qa_monthly", Integer, nullable=False, default=0),
    Column("enabled", Boolean, nullable=False, default=True),
    Column("sort_order", Integer, nullable=False, default=0),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

membership_addons_table = Table(
    "membership_addons",
    metadata,
    Column("id", ID, primary_key=True),
    Column("name", NAME, nullable=False),
    Column("unit", SHORT, nullable=False),
    Column("price_cents", Integer, nullable=False, default=0),
    Column("enabled", Boolean, nullable=False, default=True),
    Column("sort_order", Integer, nullable=False, default=0),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

user_admin_states_table = Table(
    "user_admin_states",
    metadata,
    Column("user_id", ID, primary_key=True),
    Column("status", SHORT, nullable=False, default="normal"),
    Column("freeze_reason", NAME),
    Column("frozen_at", TIMESTAMP),
    Column("frozen_by_admin_id", ID),
    Column("updated_at", TIMESTAMP, nullable=False),
)

user_memberships_table = Table(
    "user_memberships",
    metadata,
    Column("user_id", ID, primary_key=True),
    Column("plan_id", ID),
    Column("member_status", SHORT, nullable=False, default="none"),
    Column("starts_at", TIMESTAMP),
    Column("expires_at", TIMESTAMP),
    Column("source", SHORT, nullable=False, default="admin"),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

user_monthly_entitlements_table = Table(
    "user_monthly_entitlements",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False),
    Column("period_month", SHORT, nullable=False),
    Column("plan_id", ID),
    Column("transcription_minutes_total", Integer, nullable=False, default=0),
    Column("knowledge_qa_total", Integer, nullable=False, default=0),
    Column("transcription_minutes_extra", Integer, nullable=False, default=0),
    Column("knowledge_qa_extra", Integer, nullable=False, default=0),
    Column("transcription_minutes_used", Integer, nullable=False, default=0),
    Column("knowledge_qa_used", Integer, nullable=False, default=0),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
    Index("ux_user_monthly_entitlements_user_period", "user_id", "period_month", unique=True),
    Index("ix_user_monthly_entitlements_plan_period", "plan_id", "period_month"),
)

user_trial_entitlements_table = Table(
    "user_trial_entitlements",
    metadata,
    Column("user_id", ID, primary_key=True),
    Column("transcription_minutes_total", Integer, nullable=False, default=20),
    Column("knowledge_qa_total", Integer, nullable=False, default=10),
    Column("transcription_minutes_used", Integer, nullable=False, default=0),
    Column("knowledge_qa_used", Integer, nullable=False, default=0),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
)

orders_table = Table(
    "orders",
    metadata,
    Column("id", ID, primary_key=True),
    Column("channel_order_no", NAME),
    Column("user_id", ID, nullable=False),
    Column("product_type", SHORT, nullable=False, default="plan"),
    Column("plan_id", ID),
    Column("addon_id", ID),
    Column("transcription_minutes", Integer, nullable=False, default=0),
    Column("amount_cents", Integer, nullable=False, default=0),
    Column("status", SHORT, nullable=False, default="pending"),
    Column("channel", SHORT, nullable=False, default="manual"),
    Column("paid_at", TIMESTAMP),
    Column("admin_note", Text),
    Column("created_by_admin_id", ID),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
    Index("ix_orders_user_paid", "user_id", "paid_at"),
    Index("ix_orders_plan_status_paid", "plan_id", "status", "paid_at"),
    Index("ix_orders_created_at", "created_at"),
)

apple_transactions_table = Table(
    "apple_transactions",
    metadata,
    Column("id", ID, primary_key=True),
    Column("user_id", ID, nullable=False),
    Column("order_id", ID),
    Column("product_id", NAME, nullable=False),
    Column("transaction_id", NAME, nullable=False),
    Column("original_transaction_id", NAME),
    Column("environment", SHORT),
    Column("purchase_date_ms", BigInteger),
    Column("signed_transaction_info", Text, nullable=False),
    Column("status", SHORT, nullable=False, default="verified"),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
    Index("ux_apple_transactions_transaction", "transaction_id", unique=True),
    Index("ix_apple_transactions_user_created", "user_id", "created_at"),
    Index("ix_apple_transactions_original", "original_transaction_id"),
)

grant_batches_table = Table(
    "grant_batches",
    metadata,
    Column("id", ID, primary_key=True),
    Column("grant_type", SHORT, nullable=False),
    Column("audience_mode", SHORT, nullable=False),
    Column("plan_id", ID),
    Column("start_date", SHORT),
    Column("end_date", SHORT),
    Column("before_date", SHORT),
    Column("status_filter", SHORT),
    Column("keyword", NAME),
    Column("scope_label", NAME, nullable=False),
    Column("recipient_count", Integer, nullable=False, default=0),
    Column("member_days", Integer, nullable=False, default=0),
    Column("transcription_minutes", Integer, nullable=False, default=0),
    Column("knowledge_qa_count", Integer, nullable=False, default=0),
    Column("reason", NAME),
    Column("status", SHORT, nullable=False, default="completed"),
    Column("created_by_admin_id", ID),
    Column("created_at", TIMESTAMP, nullable=False),
    Index("ix_grant_batches_created_at", "created_at"),
)

grant_items_table = Table(
    "grant_items",
    metadata,
    Column("id", ID, primary_key=True),
    Column("batch_id", ID, nullable=False),
    Column("user_id", ID, nullable=False),
    Column("member_days", Integer, nullable=False, default=0),
    Column("transcription_minutes", Integer, nullable=False, default=0),
    Column("knowledge_qa_count", Integer, nullable=False, default=0),
    Column("status", SHORT, nullable=False, default="completed"),
    Column("created_at", TIMESTAMP, nullable=False),
    Index("ix_grant_items_batch", "batch_id"),
    Index("ix_grant_items_user", "user_id"),
)

announcements_table = Table(
    "announcements",
    metadata,
    Column("id", ID, primary_key=True),
    Column("title", NAME, nullable=False),
    Column("content", Text, nullable=False),
    Column("audience", SHORT, nullable=False, default="all"),
    Column("status", SHORT, nullable=False, default="draft"),
    Column("pinned", Boolean, nullable=False, default=False),
    Column("publish_at", TIMESTAMP),
    Column("expire_at", TIMESTAMP),
    Column("target_count", Integer, nullable=False, default=0),
    Column("read_count", Integer, nullable=False, default=0),
    Column("created_by_admin_id", ID),
    Column("created_at", TIMESTAMP, nullable=False),
    Column("updated_at", TIMESTAMP, nullable=False),
    Index("ix_announcements_status_publish", "status", "publish_at"),
)

admin_change_records_table = Table(
    "admin_change_records",
    metadata,
    Column("id", ID, primary_key=True),
    Column("admin_id", ID),
    Column("user_id", ID),
    Column("entity_type", SHORT, nullable=False),
    Column("entity_id", ID),
    Column("action_type", SHORT, nullable=False),
    Column("before_value", Text),
    Column("after_value", Text),
    Column("note", Text),
    Column("created_at", TIMESTAMP, nullable=False),
    Index("ix_admin_change_records_user_time", "user_id", "created_at"),
    Index("ix_admin_change_records_action_time", "action_type", "created_at"),
)

engine_options: dict = {"pool_pre_ping": True, "pool_recycle": 3600}
if settings.database_url.startswith("postgresql"):
    engine_options["client_encoding"] = "utf8"
elif settings.database_url.startswith(("mysql", "mariadb")):
    engine_options["connect_args"] = {
        "connect_timeout": settings.db_connect_timeout_seconds,
        "read_timeout": settings.db_read_timeout_seconds,
        "write_timeout": settings.db_write_timeout_seconds,
    }
    engine_options["pool_size"] = 5
    engine_options["max_overflow"] = 10
    engine_options["pool_timeout"] = 20
engine = create_engine(settings.database_url, **engine_options)


def init_db() -> None:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.upload_dir.mkdir(parents=True, exist_ok=True)
    if engine.dialect.name in {"mysql", "mariadb"} and _mysql_schema_ready():
        _create_missing_mysql_tables()
        _ensure_user_columns()
        _ensure_auth_columns()
        _ensure_tasks_columns()
        _ensure_results_columns()
        _ensure_knowledge_columns()
        _ensure_scheduled_meetings_columns()
        _ensure_orders_columns()
        _ensure_performance_indexes()
        _ensure_admin_indexes()
        return
    metadata.create_all(engine, tables=_auto_create_tables())
    _ensure_user_columns()
    _ensure_auth_columns()
    _ensure_tasks_columns()
    _ensure_results_columns()
    _ensure_knowledge_columns()
    _ensure_scheduled_meetings_columns()
    _ensure_orders_columns()
    _ensure_performance_indexes()
    _ensure_admin_indexes()


def _create_missing_mysql_tables() -> None:
    table_names = _table_names()
    missing_tables = [
        table
        for table in metadata.sorted_tables
        if table.name not in table_names and table.name not in MANUAL_MIGRATION_TABLE_NAMES
    ]
    if not missing_tables:
        return
    with engine.begin() as conn:
        for table in missing_tables:
            table.create(conn, checkfirst=False)


def _auto_create_tables() -> list[Table]:
    return [table for table in metadata.sorted_tables if table.name not in MANUAL_MIGRATION_TABLE_NAMES]


def _table_names() -> set[str]:
    if engine.dialect.name in {"mysql", "mariadb"}:
        return {str(row[0]) for row in _mysql_rows("SHOW TABLES")}
    return set(inspect(engine).get_table_names())


def _column_names(table_name: str) -> set[str]:
    if engine.dialect.name in {"mysql", "mariadb"}:
        quoted = _quote_identifier(table_name)
        return {str(row[0]) for row in _mysql_rows(f"SHOW COLUMNS FROM {quoted}")}
    return {column["name"] for column in inspect(engine).get_columns(table_name)}


def _index_names(table_name: str) -> set[str]:
    if engine.dialect.name in {"mysql", "mariadb"}:
        quoted = _quote_identifier(table_name)
        return {str(row[2]) for row in _mysql_rows(f"SHOW INDEX FROM {quoted}")}
    return {index["name"] for index in inspect(engine).get_indexes(table_name)}


def _mysql_rows(statement: str) -> list:
    for attempt in range(3):
        try:
            with engine.connect() as conn:
                return list(conn.execute(text(statement)))
        except OperationalError as exc:
            engine.dispose()
            if attempt >= 2:
                raise exc
            time.sleep(0.8 * (attempt + 1))
    return []


def _execute_schema_statements(statements: list[str]) -> None:
    if not statements:
        return
    with engine.begin() as conn:
        for statement in statements:
            conn.execute(text(statement))


def _quote_identifier(identifier: str) -> str:
    return f"`{identifier.replace('`', '``')}`"


def _mysql_schema_ready() -> bool:
    for attempt in range(3):
        try:
            with engine.connect() as conn:
                conn.execute(text("SELECT 1 FROM users LIMIT 1"))
            return True
        except Exception:
            engine.dispose()
            if attempt < 2:
                time.sleep(0.8 * (attempt + 1))
    return False


def _ensure_user_columns() -> None:
    table_names = _table_names()
    statements: list[str] = []
    if "files" in table_names:
        columns = _column_names("files")
        if "user_id" not in columns:
            statements.append("ALTER TABLE files ADD COLUMN user_id VARCHAR(255) NOT NULL DEFAULT 'user-336496'")
    if "tasks" in table_names:
        columns = _column_names("tasks")
        if "user_id" not in columns:
            statements.append("ALTER TABLE tasks ADD COLUMN user_id VARCHAR(255) NOT NULL DEFAULT 'user-336496'")
    if "knowledge_chunks" in table_names:
        columns = _column_names("knowledge_chunks")
        if "user_id" not in columns:
            statements.append("ALTER TABLE knowledge_chunks ADD COLUMN user_id VARCHAR(255) NOT NULL DEFAULT 'user-336496'")
    _execute_schema_statements(statements)


def _ensure_auth_columns() -> None:
    if "users" not in _table_names():
        return
    columns = _column_names("users")
    statements: list[str] = []
    if "phone_e164" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN phone_e164 VARCHAR(64) NULL")
    if "phone_verified_at" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN phone_verified_at VARCHAR(64) NULL")
    if "last_login_at" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN last_login_at VARCHAR(64) NULL")
    if "password_hash" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN password_hash VARCHAR(255) NULL")
    if "password_set_at" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN password_set_at VARCHAR(64) NULL")
    if "password_updated_at" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN password_updated_at VARCHAR(64) NULL")
    if "password_failed_attempts" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN password_failed_attempts INTEGER NOT NULL DEFAULT 0")
    if "password_locked_until" not in columns:
        statements.append("ALTER TABLE users ADD COLUMN password_locked_until VARCHAR(64) NULL")
    _execute_schema_statements(statements)
    indexes = _index_names("users")
    if "ux_users_phone_e164" not in indexes:
        _execute_schema_statements(["CREATE UNIQUE INDEX ux_users_phone_e164 ON users (phone_e164)"])


def _ensure_results_columns() -> None:
    if "results" not in _table_names():
        return
    columns = _column_names("results")
    text_default_suffix = "TEXT" if engine.dialect.name in {"mysql", "mariadb"} else "TEXT NOT NULL DEFAULT '[]'"
    statements: list[str] = []
    if "topics_json" not in columns:
        statements.append(f"ALTER TABLE results ADD COLUMN topics_json {text_default_suffix}")
    if "risks_json" not in columns:
        statements.append(f"ALTER TABLE results ADD COLUMN risks_json {text_default_suffix}")
    if "participants" not in columns:
        statements.append("ALTER TABLE results ADD COLUMN participants TEXT")
    if "tags_json" not in columns:
        statements.append(f"ALTER TABLE results ADD COLUMN tags_json {text_default_suffix}")
    _execute_schema_statements(statements)


def _ensure_tasks_columns() -> None:
    if "tasks" not in _table_names():
        return
    columns = _column_names("tasks")
    statements: list[str] = []
    if "progress_percent" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN progress_percent REAL NOT NULL DEFAULT 0")
    if "progress_label" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN progress_label TEXT")
    if "progress_stage" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN progress_stage TEXT")
    if "sync_scope" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN sync_scope VARCHAR(64) NOT NULL DEFAULT 'cloud'")
    if "knowledge_scope" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN knowledge_scope VARCHAR(64) NOT NULL DEFAULT 'cloud'")
    if "is_private" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE")
    if "device_id" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN device_id VARCHAR(255)")
    if "client_task_id" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN client_task_id VARCHAR(255)")
    if "confirmed" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN confirmed BOOLEAN NOT NULL DEFAULT FALSE")
    if "created_at_millis" not in columns:
        statements.append("ALTER TABLE tasks ADD COLUMN created_at_millis BIGINT")
    _execute_schema_statements(statements)


def _ensure_knowledge_columns() -> None:
    if "knowledge_chunks" not in _table_names():
        return
    columns = _column_names("knowledge_chunks")
    statements: list[str] = []
    if "knowledge_scope" not in columns:
        statements.append("ALTER TABLE knowledge_chunks ADD COLUMN knowledge_scope VARCHAR(64) NOT NULL DEFAULT 'cloud'")
    if "is_private" not in columns:
        statements.append("ALTER TABLE knowledge_chunks ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE")
    _execute_schema_statements(statements)
    indexes = _index_names("knowledge_chunks")
    index_statements: list[str] = []
    if "ix_knowledge_chunks_user_scope_private" not in indexes:
        index_statements.append(
            "CREATE INDEX ix_knowledge_chunks_user_scope_private "
            "ON knowledge_chunks (user_id, knowledge_scope, is_private)"
        )
    if "ix_knowledge_chunks_user_task" not in indexes:
        index_statements.append("CREATE INDEX ix_knowledge_chunks_user_task ON knowledge_chunks (user_id, task_id)")
    if "ix_knowledge_chunks_user_type_created" not in indexes:
        index_statements.append(
            "CREATE INDEX ix_knowledge_chunks_user_type_created "
            "ON knowledge_chunks (user_id, chunk_type, created_at)"
        )
    _execute_schema_statements(index_statements)


def _ensure_orders_columns() -> None:
    if "orders" not in _table_names():
        return
    columns = _column_names("orders")
    statements: list[str] = []
    if "product_type" not in columns:
        statements.append("ALTER TABLE orders ADD COLUMN product_type VARCHAR(64) NOT NULL DEFAULT 'plan'")
    if "addon_id" not in columns:
        statements.append("ALTER TABLE orders ADD COLUMN addon_id VARCHAR(64) NULL")
    if "transcription_minutes" not in columns:
        statements.append("ALTER TABLE orders ADD COLUMN transcription_minutes INTEGER NOT NULL DEFAULT 0")
    _execute_schema_statements(statements)


def _ensure_performance_indexes() -> None:
    table_names = _table_names()
    statements: list[str] = []
    if "tasks" in table_names:
        indexes = _index_names("tasks")
        if "ix_tasks_user_scope_created" not in indexes:
            statements.append("CREATE INDEX ix_tasks_user_scope_created ON tasks (user_id, sync_scope, created_at)")
        if "ix_tasks_user_status_updated" not in indexes:
            statements.append("CREATE INDEX ix_tasks_user_status_updated ON tasks (user_id, status, updated_at)")
        if "ix_tasks_user_client_task" not in indexes:
            statements.append("CREATE INDEX ix_tasks_user_client_task ON tasks (user_id, client_task_id)")
    if "files" in table_names:
        indexes = _index_names("files")
        if "ix_files_user_id" not in indexes:
            statements.append("CREATE INDEX ix_files_user_id ON files (user_id)")
    _execute_schema_statements(statements)


def _ensure_admin_indexes() -> None:
    table_names = _table_names()
    statements: list[str] = []
    if "user_monthly_entitlements" in table_names:
        indexes = _index_names("user_monthly_entitlements")
        if "ux_user_monthly_entitlements_user_period" not in indexes:
            statements.append(
                "CREATE UNIQUE INDEX ux_user_monthly_entitlements_user_period "
                "ON user_monthly_entitlements (user_id, period_month)"
            )
        if "ix_user_monthly_entitlements_plan_period" not in indexes:
            statements.append(
                "CREATE INDEX ix_user_monthly_entitlements_plan_period "
                "ON user_monthly_entitlements (plan_id, period_month)"
            )
    if "user_trial_entitlements" in table_names:
        indexes = _index_names("user_trial_entitlements")
        if "ix_user_trial_entitlements_updated_at" not in indexes:
            statements.append("CREATE INDEX ix_user_trial_entitlements_updated_at ON user_trial_entitlements (updated_at)")
    if "orders" in table_names:
        indexes = _index_names("orders")
        if "ix_orders_user_paid" not in indexes:
            statements.append("CREATE INDEX ix_orders_user_paid ON orders (user_id, paid_at)")
        if "ix_orders_plan_status_paid" not in indexes:
            statements.append("CREATE INDEX ix_orders_plan_status_paid ON orders (plan_id, status, paid_at)")
        if "ix_orders_created_at" not in indexes:
            statements.append("CREATE INDEX ix_orders_created_at ON orders (created_at)")
    if "users" in table_names and "ix_users_created_at" not in _index_names("users"):
        statements.append("CREATE INDEX ix_users_created_at ON users (created_at)")
    if "grant_batches" in table_names and "ix_grant_batches_created_at" not in _index_names("grant_batches"):
        statements.append("CREATE INDEX ix_grant_batches_created_at ON grant_batches (created_at)")
    if "grant_items" in table_names:
        indexes = _index_names("grant_items")
        if "ix_grant_items_batch" not in indexes:
            statements.append("CREATE INDEX ix_grant_items_batch ON grant_items (batch_id)")
        if "ix_grant_items_user" not in indexes:
            statements.append("CREATE INDEX ix_grant_items_user ON grant_items (user_id)")
    if "announcements" in table_names and "ix_announcements_status_publish" not in _index_names("announcements"):
        statements.append("CREATE INDEX ix_announcements_status_publish ON announcements (status, publish_at)")
    if "announcements" in table_names and "ix_announcements_updated_at" not in _index_names("announcements"):
        statements.append("CREATE INDEX ix_announcements_updated_at ON announcements (updated_at)")
    if "admin_change_records" in table_names:
        indexes = _index_names("admin_change_records")
        if "ix_admin_change_records_user_time" not in indexes:
            statements.append("CREATE INDEX ix_admin_change_records_user_time ON admin_change_records (user_id, created_at)")
        if "ix_admin_change_records_action_time" not in indexes:
            statements.append("CREATE INDEX ix_admin_change_records_action_time ON admin_change_records (action_type, created_at)")
    _execute_schema_statements(statements)


def _ensure_scheduled_meetings_columns() -> None:
    if "scheduled_meetings" not in _table_names():
        return
    columns = _column_names("scheduled_meetings")
    statements: list[str] = []
    if "calendar_event_id" not in columns:
        statements.append("ALTER TABLE scheduled_meetings ADD COLUMN calendar_event_id BIGINT")
    if "note" not in columns:
        statements.append("ALTER TABLE scheduled_meetings ADD COLUMN note TEXT NULL")
    _execute_schema_statements(statements)


@contextmanager
def connect() -> Iterator[Connection]:
    transaction = None
    for attempt in range(3):
        try:
            transaction = engine.begin()
            conn = transaction.__enter__()
            break
        except OperationalError as exc:
            engine.dispose()
            if attempt >= 2:
                raise exc
            time.sleep(0.8 * (attempt + 1))
    else:
        raise RuntimeError("数据库连接失败")
    try:
        yield conn
    except BaseException as exc:
        transaction.__exit__(type(exc), exc, exc.__traceback__)
        transaction = None
        raise
    finally:
        if transaction is not None:
            transaction.__exit__(None, None, None)
