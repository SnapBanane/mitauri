"""Makes this laptop broadcast its own presence token, in the same format as the phone, through BlueZ.
Linux only: elsewhere it does nothing, and the laptop simply shows as away."""

import asyncio
import logging
import threading
import time

from .presence import COMPANY_ID, TOKEN_WINDOW_SECONDS, beacon_payload, device_key

ADVERT_PATH = "/de/mitauri/beacon"
ADAPTER_PATH = "/org/bluez/hci0"
CHECK_SECONDS = 30  # a timer alone would run late after the laptop sleeps
RETRY_SECONDS = 60

log = logging.getLogger(__name__)


def start_laptop_beacon(key: bytes) -> None:
    try:
        from dbus_fast import Variant  # noqa: F401
    except ImportError:
        log.info("Laptop beacon needs BlueZ, so it's off here")
        return
    laptop_key = device_key(key, "laptop")
    threading.Thread(target=lambda: asyncio.run(_run_forever(laptop_key)), daemon=True).start()


async def _run_forever(key: bytes) -> None:
    last_error = None
    while True:
        try:
            await _advertise(key)
        except Exception as error:  # Bluetooth being off or restarting must never reach the server
            if str(error) != last_error:
                log.warning("Laptop beacon stopped, will keep retrying: %s", error)
                last_error = str(error)
        await asyncio.sleep(RETRY_SECONDS)


async def _advertise(key: bytes) -> None:
    from dbus_fast import BusType, Variant
    from dbus_fast.aio import MessageBus
    from dbus_fast.service import PropertyAccess, ServiceInterface, dbus_method, dbus_property

    class Advert(ServiceInterface):
        def __init__(self, payload: bytes):
            super().__init__("org.bluez.LEAdvertisement1")
            self._payload = payload

        @dbus_method()
        def Release(self):
            pass

        # BlueZ reads these when the advert registers, and "broadcast" means nobody can connect to it.
        @dbus_property(access=PropertyAccess.READ)
        def Type(self) -> "s":
            return "broadcast"

        @dbus_property(access=PropertyAccess.READ)
        def ManufacturerData(self) -> "a{qv}":
            return {COMPANY_ID: Variant("ay", self._payload)}

    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    try:
        info = await bus.introspect("org.bluez", ADAPTER_PATH)
        manager = bus.get_proxy_object("org.bluez", ADAPTER_PATH, info).get_interface("org.bluez.LEAdvertisingManager1")
        while True:
            window = int(time.time() // TOKEN_WINDOW_SECONDS)
            advert = Advert(beacon_payload(key, window))
            bus.export(ADVERT_PATH, advert)
            await manager.call_register_advertisement(ADVERT_PATH, {})
            log.info("Laptop beacon on")
            while int(time.time() // TOKEN_WINDOW_SECONDS) == window:
                await asyncio.sleep(CHECK_SECONDS)
            await manager.call_unregister_advertisement(ADVERT_PATH)
            bus.unexport(ADVERT_PATH, advert)
    finally:
        bus.disconnect()
