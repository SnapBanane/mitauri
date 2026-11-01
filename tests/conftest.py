import pytest


@pytest.fixture(autouse=True)
def private_data_folder(tmp_path_factory, monkeypatch):
    """No test may reach the real data folder, whose audit log is a security record."""
    monkeypatch.setenv("MITAURI_DATA_DIR", str(tmp_path_factory.mktemp("data")))
