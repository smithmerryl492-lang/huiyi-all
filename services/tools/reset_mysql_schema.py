import os

import pymysql
from sqlalchemy.engine import make_url


DDL = [
    """
    CREATE TABLE users (
        id VARCHAR(64) PRIMARY KEY,
        username VARCHAR(64) NOT NULL UNIQUE,
        display_name VARCHAR(255) NOT NULL,
        created_at VARCHAR(64) NOT NULL,
        updated_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE files (
        id VARCHAR(64) PRIMARY KEY,
        user_id VARCHAR(64) NOT NULL,
        original_name VARCHAR(255) NOT NULL,
        stored_path TEXT NOT NULL,
        content_type VARCHAR(128) NOT NULL,
        size_bytes BIGINT NOT NULL,
        created_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE tasks (
        id VARCHAR(64) PRIMARY KEY,
        user_id VARCHAR(64) NOT NULL,
        file_id VARCHAR(64) NOT NULL,
        client_task_id VARCHAR(64) NULL,
        title VARCHAR(255) NOT NULL,
        source VARCHAR(64) NOT NULL,
        status VARCHAR(64) NOT NULL,
        error_message TEXT NULL,
        progress_percent DOUBLE NOT NULL DEFAULT 0,
        progress_label TEXT NULL,
        progress_stage VARCHAR(64) NULL,
        sync_scope VARCHAR(64) NOT NULL DEFAULT 'cloud',
        knowledge_scope VARCHAR(64) NOT NULL DEFAULT 'cloud',
        is_private BOOLEAN NOT NULL DEFAULT FALSE,
        device_id VARCHAR(255) NULL,
        confirmed BOOLEAN NOT NULL DEFAULT FALSE,
        created_at_millis BIGINT NULL,
        created_at VARCHAR(64) NOT NULL,
        updated_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE results (
        task_id VARCHAR(64) PRIMARY KEY,
        source_file_path TEXT NOT NULL,
        summary TEXT NOT NULL,
        topics_json TEXT NOT NULL,
        decisions_json TEXT NOT NULL,
        todos_json TEXT NOT NULL,
        risks_json TEXT NOT NULL,
        transcripts_json TEXT NOT NULL,
        generated_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE scheduled_meetings (
        id VARCHAR(64) PRIMARY KEY,
        user_id VARCHAR(64) NOT NULL,
        time VARCHAR(64) NOT NULL,
        title VARCHAR(255) NOT NULL,
        participants TEXT NOT NULL,
        duration_label VARCHAR(64) NOT NULL,
        reminder_label VARCHAR(64) NOT NULL,
        start_at_millis BIGINT NULL,
        end_at_millis BIGINT NULL,
        created_at_millis BIGINT NOT NULL,
        status VARCHAR(64) NOT NULL,
        updated_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE knowledge_chunks (
        id VARCHAR(64) PRIMARY KEY,
        user_id VARCHAR(64) NOT NULL,
        task_id VARCHAR(64) NOT NULL,
        chunk_type VARCHAR(64) NOT NULL,
        title VARCHAR(255) NOT NULL,
        text TEXT NOT NULL,
        speaker VARCHAR(64) NULL,
        timestamp VARCHAR(64) NULL,
        start_ms BIGINT NULL,
        end_ms BIGINT NULL,
        knowledge_scope VARCHAR(64) NOT NULL DEFAULT 'cloud',
        is_private BOOLEAN NOT NULL DEFAULT FALSE,
        embedding_json TEXT NOT NULL,
        created_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
]


TABLES = [
    "knowledge_chunks",
    "results",
    "scheduled_meetings",
    "tasks",
    "files",
    "users",
]


def main() -> int:
    raw_url = os.environ.get("HUIXIAO_DATABASE_URL")
    if not raw_url:
        raise RuntimeError("HUIXIAO_DATABASE_URL is required")
    url = make_url(raw_url)
    charset = str(url.query.get("charset", "utf8mb4"))
    conn = pymysql.connect(
        host=url.host or "127.0.0.1",
        port=url.port or 3306,
        user=url.username or "",
        password=url.password or "",
        database=url.database or "",
        charset=charset,
        autocommit=False,
        connect_timeout=10,
        read_timeout=30,
        write_timeout=30,
    )
    try:
        with conn.cursor() as cursor:
            for table in TABLES:
                cursor.execute(f"DROP TABLE IF EXISTS {table}")
            for ddl in DDL:
                cursor.execute(ddl)
            cursor.executemany(
                """
                INSERT INTO users (id, username, display_name, created_at, updated_at)
                VALUES (%s, %s, %s, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                [
                    ("user-336496", "336496", "用户 336496"),
                    ("user-123456", "123456", "用户 123456"),
                ],
            )
            counts = {}
            for table in reversed(TABLES):
                cursor.execute(f"SELECT COUNT(*) FROM {table}")
                counts[table] = cursor.fetchone()[0]
        conn.commit()
        print({"database": url.database, "counts": counts})
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
