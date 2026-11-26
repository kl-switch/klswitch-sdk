"""Optional web framework adapters.

Each adapter is an optional import: install the matching framework yourself
(``pip install flask`` or ``pip install fastapi httpx``) — they are *not* hard
dependencies of the SDK. Import the adapter you want directly::

    from klswitch.adapters.flask import KLSwitchExtension
    from klswitch.adapters.fastapi import klswitch_depends
"""
