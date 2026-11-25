"""Alembic migration upgrade/downgrade and schema constraint tests."""

from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect

BACKEND_ROOT = Path(__file__).resolve().parents[1]


def test_alembic_upgrade_and_downgrade_cycle(tmp_path: Path) -> None:
    """Verify full Alembic upgrade to head, table creation, downgrade to base, and re-upgrade."""
    db_file = tmp_path / "migration_cycle.db"
    url = f"sqlite:///{db_file.as_posix()}"

    alembic_cfg = Config(str(BACKEND_ROOT / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(BACKEND_ROOT / "alembic"))
    alembic_cfg.set_main_option("sqlalchemy.url", url)

    command.upgrade(alembic_cfg, "head")
    engine = create_engine(url)
    inspector = inspect(engine)
    tables = set(inspector.get_table_names())
    assert {
        "institutions",
        "users",
        "students",
        "calibration_profiles",
        "scoring_standards",
        "examinations",
        "attempts",
        "audit_logs",
    }.issubset(tables)

    command.downgrade(alembic_cfg, "base")
    inspector_after_down = inspect(engine)
    remaining_app_tables = set(inspector_after_down.get_table_names()) - {"alembic_version"}
    assert remaining_app_tables == set()

    command.upgrade(alembic_cfg, "head")
    engine.dispose()
