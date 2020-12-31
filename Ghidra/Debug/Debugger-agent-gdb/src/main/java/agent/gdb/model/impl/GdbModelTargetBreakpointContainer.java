/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package agent.gdb.model.impl;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import agent.gdb.manager.GdbCause;
import agent.gdb.manager.GdbEventsListenerAdapter;
import agent.gdb.manager.breakpoint.GdbBreakpointInfo;
import agent.gdb.manager.breakpoint.GdbBreakpointType;
import ghidra.async.AsyncFence;
import ghidra.dbg.agent.DefaultTargetObject;
import ghidra.dbg.target.TargetBreakpointContainer;
import ghidra.dbg.target.TargetBreakpointSpec.TargetBreakpointKind;
import ghidra.program.model.address.AddressRange;
import ghidra.util.Msg;
import ghidra.util.datastruct.WeakValueHashMap;

public class GdbModelTargetBreakpointContainer
		extends DefaultTargetObject<GdbModelTargetBreakpointSpec, GdbModelTargetSession>
		implements TargetBreakpointContainer<GdbModelTargetBreakpointContainer>,
		GdbEventsListenerAdapter {

	protected static final TargetBreakpointKindSet SUPPORTED_KINDS =
		TargetBreakpointKindSet.of(TargetBreakpointKind.values());

	protected final GdbModelImpl impl;

	protected final Map<Long, GdbModelTargetBreakpointSpec> specsByNumber =
		new WeakValueHashMap<>();

	public GdbModelTargetBreakpointContainer(GdbModelTargetSession session) {
		super(session.impl, session, "Breakpoints", "BreakpointContainer");
		this.impl = session.impl;

		impl.gdb.addEventsListener(this);

		changeAttributes(List.of(), Map.of(  //
			// TODO: Seems terrible to duplicate this static attribute on each instance
			SUPPORTED_BREAK_KINDS_ATTRIBUTE_NAME, SUPPORTED_KINDS //
		), "Initialized");
	}

	@Override
	public void breakpointCreated(GdbBreakpointInfo info, GdbCause cause) {
		changeElements(List.of(), List.of(getTargetBreakpointSpec(info)), "Created");
	}

	@Override
	public void breakpointModified(GdbBreakpointInfo newInfo, GdbBreakpointInfo oldInfo,
			GdbCause cause) {
		getTargetBreakpointSpec(oldInfo).updateInfo(oldInfo, newInfo, "Modified");
	}

	protected void breakpointHit(long bpId, GdbModelTargetStackFrame frame) {
		GdbModelTargetBreakpointSpec spec = getTargetBreakpointSpecIfPresent(bpId);
		if (spec == null) {
			Msg.error(this, "Stopped for breakpoint unknown to the agent: " + bpId + " (pc=" +
				frame.getProgramCounter() + ")");
			return;
		}

		GdbModelTargetBreakpointLocation eb = spec.findEffective(frame);
		if (eb == null) {
			Msg.warn(this, "Stopped for a breakpoint whose location is unknown to the agent: " +
				spec + " (pc=" + frame.getProgramCounter() + ")");
			//return; // Not idea, but eb == null should be fine, since the spec holds the actions 
		}
		listeners.fire(TargetBreakpointListener.class)
				.breakpointHit(this, frame.thread, frame, spec, eb);
		spec.breakpointHit(frame, eb);
	}

	@Override
	public void breakpointDeleted(GdbBreakpointInfo info, GdbCause cause) {
		synchronized (this) {
			specsByNumber.remove(info.getNumber());
		}
		changeElements(List.of(GdbModelTargetBreakpointSpec.indexBreakpoint(info)), List.of(),
			"Deleted");
	}

	protected CompletableFuture<Void> doPlaceBreakpoint(Set<TargetBreakpointKind> kinds,
			Function<GdbBreakpointType, CompletableFuture<?>> placer) {
		AsyncFence fence = new AsyncFence();
		if (kinds.contains(TargetBreakpointKind.READ) &&
			kinds.contains(TargetBreakpointKind.WRITE)) {
			fence.include(placer.apply(GdbBreakpointType.ACCESS_WATCHPOINT));
		}
		else if (kinds.contains(TargetBreakpointKind.READ)) {
			fence.include(placer.apply(GdbBreakpointType.READ_WATCHPOINT));
		}
		else if (kinds.contains(TargetBreakpointKind.WRITE)) {
			fence.include(placer.apply(GdbBreakpointType.HW_WATCHPOINT));
		}
		if (kinds.contains(TargetBreakpointKind.EXECUTE)) {
			fence.include(placer.apply(GdbBreakpointType.HW_BREAKPOINT));
		}
		if (kinds.contains(TargetBreakpointKind.SOFTWARE)) {
			fence.include(placer.apply(GdbBreakpointType.BREAKPOINT));
		}
		return fence.ready().exceptionally(GdbModelImpl::translateEx);
	}

	@Override
	public CompletableFuture<Void> placeBreakpoint(String expression,
			Set<TargetBreakpointKind> kinds) {
		return doPlaceBreakpoint(kinds, t -> impl.gdb.insertBreakpoint(expression, t));
	}

	@Override
	public CompletableFuture<Void> placeBreakpoint(AddressRange range,
			Set<TargetBreakpointKind> kinds) {
		// TODO: Consider how to translate address spaces
		long offset = range.getMinAddress().getOffset();
		int len = (int) range.getLength();
		return doPlaceBreakpoint(kinds, t -> impl.gdb.insertBreakpoint(offset, len, t));
	}

	public synchronized GdbModelTargetBreakpointSpec getTargetBreakpointSpec(
			GdbBreakpointInfo info) {
		return specsByNumber.computeIfAbsent(info.getNumber(),
			i -> new GdbModelTargetBreakpointSpec(this, info));
	}

	public synchronized GdbModelTargetBreakpointSpec getTargetBreakpointSpecIfPresent(long number) {
		return specsByNumber.get(number);
	}

	protected void updateUsingBreakpoints(Map<Long, GdbBreakpointInfo> byNumber) {
		List<GdbModelTargetBreakpointSpec> specs;
		synchronized (this) {
			specs = byNumber.values()
					.stream()
					.map(this::getTargetBreakpointSpec)
					.collect(Collectors.toList());
		}
		setElements(specs, "Refreshed");
	}

	@Override
	public CompletableFuture<Void> requestElements(boolean refresh) {
		if (!refresh) {
			updateUsingBreakpoints(impl.gdb.getKnownBreakpoints());
		}
		return impl.gdb.listBreakpoints().thenAccept(this::updateUsingBreakpoints);
	}
}