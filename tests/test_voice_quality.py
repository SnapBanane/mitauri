from mitauri.speech import voice_files


def test_voice_stays_high_unless_medium_is_asked_for(monkeypatch):
    monkeypatch.delenv("MITAURI_VOICE_QUALITY", raising=False)
    assert voice_files("de") == ("de/de_DE/thorsten/high", "de_DE-thorsten-high")
    monkeypatch.setenv("MITAURI_VOICE_QUALITY", "nonsense")
    assert voice_files("en") == ("en/en_US/ryan/high", "en_US-ryan-high")


def test_medium_voice_can_be_chosen(monkeypatch):
    monkeypatch.setenv("MITAURI_VOICE_QUALITY", "medium")
    assert voice_files("de") == ("de/de_DE/thorsten/medium", "de_DE-thorsten-medium")
