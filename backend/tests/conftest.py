"""Isolated test configuration and fixtures for AthletiQ backend tests."""

import os
import secrets
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import event
from sqlalchemy.orm import Session, sessionmaker

# Configure isolated test environment before importing app modules.
_TEST_JWT_SECRET = secrets.token_hex(32)
os.environ["ATHLETIQ_ENV"] = "test"
os.environ["ATHLETIQ_JWT_SECRET"] = _TEST_JWT_SECRET
os.environ["ATHLETIQ_JWT_ALGORITHM"] = "HS256"
os.environ["ATHLETIQ_DATABASE_URL"] = "sqlite:///./test_placeholder.db"

from app.core.config import get_settings  # noqa: E402
from app.db.session import build_engine, get_db  # noqa: E402
from app.main import create_app  # noqa: E402

BACKEND_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture()
def test_db_url(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> str:
    """Create a fresh temporary SQLite database and migrate it with Alembic."""
    db_file = tmp_path / "athletiq_test.db"
    url = f"sqlite:///{db_file.as_posix()}"
    monkeypatch.setenv("ATHLETIQ_DATABASE_URL", url)
    monkeypatch.setenv("ATHLETIQ_JWT_SECRET", _TEST_JWT_SECRET)
    get_settings.cache_clear()

    alembic_cfg = Config(str(BACKEND_ROOT / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(BACKEND_ROOT / "alembic"))
    alembic_cfg.set_main_option("sqlalchemy.url", url)
    command.upgrade(alembic_cfg, "head")
    return url


@pytest.fixture()
def db_session_factory(test_db_url: str) -> sessionmaker[Session]:
    """Provide a SQLAlchemy session factory with foreign keys enforced."""
    engine = build_engine(test_db_url)

    @event.listens_for(engine, "connect")
    def _enable_sqlite_fk(dbapi_connection, _connection_record):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()

    return sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


@pytest.fixture()
def db_session(db_session_factory: sessionmaker[Session]) -> Session:
    """Yield an isolated DB session for fixture setup and assertions."""
    session = db_session_factory()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture()
def client(db_session_factory: sessionmaker[Session]) -> TestClient:
    """Provide a FastAPI TestClient wired to the isolated test DB."""
    app = create_app()

    def _override_get_db():
        session = db_session_factory()
        try:
            yield session
        finally:
            session.close()

    app.dependency_overrides[get_db] = _override_get_db
    with TestClient(app) as test_client:
        yield test_client
