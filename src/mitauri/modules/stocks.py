from .. import stocks, toolbox

tool = toolbox.tool_for("stocks")


@tool
def stock_quotes(symbols: list[str]) -> dict:
    """Possibly delayed stock prices. US symbols are plain, like AAPL, and others name the market, like SAP.DE.

    Only for looking things up. Never give this as a recommendation to buy or sell.
    """
    try:
        return {"quotes": stocks.quotes(symbols), "note": "May be delayed."}
    except (OSError, ValueError, KeyError, TypeError):
        return {"error": "The stock service couldn't be reached."}
