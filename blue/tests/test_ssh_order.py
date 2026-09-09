import pytest
from blue.workflow import run, workflow as graph
from package_once_blue.workflow import wire_fn

@pytest.mark.parametrize("event", ["create", "build"])
@pytest.mark.parametrize("failure", [False, True])
async def test_alias_update_finishes_before_remote_convergence(event, failure):
    seen=[]
    opts={"blue/event":event}
    assert wire_fn("once/tofu-smtp-post",opts)[1:]==("once/ansible-local",)
    assert wire_fn("once/ansible-local",opts)[1:]==("once/ansible-remote",)
    def wire(step, run_opts):
        original=wire_fn(step,run_opts)
        async def fake(current):
            seen.append(step)
            return {**current,"blue/exit":1 if failure and step=="once/ansible-local" else 0}
        return (fake, *original[1:]) if original else None
    result=await run(graph(start="once/tofu-smtp-post",wire_fn=wire),opts)
    assert seen[:2]==["once/tofu-smtp-post","once/ansible-local"]
    assert ("once/ansible-remote" in seen) is not failure
    assert result["blue/exit"]==(1 if failure else 0)
