<script lang="ts">
  import { session as sessionStore, settings } from "../app/context";
  import type { RoomState } from "../lib/syncplay/client";

  /**
   * Shown over any screen while a YarmiplayServerTV host decides about this browser, or after they said no
   * (MobileDeviceAccess.kt and YarmiplayUi.kt's deviceAccessPrompt, in the same words).
   */
  const session = $derived($sessionStore);
  let room = $state<RoomState | null>(null);

  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => (room = r));
  });

  type Prompt = { kind: "waiting"; fingerprint: string } | { kind: "notApproved" } | { kind: "refused"; title: string; message: string };

  const prompt = $derived.by((): Prompt | null => {
    const d = room?.yarmiplay.device;
    switch (d?.kind) {
      case "pending":
        return { kind: "waiting", fingerprint: d.fingerprint };
      case "required":
        return { kind: "notApproved" };
      case "denied":
        return { kind: "refused", title: "The host turned this device down", message: "You can ask again, or ask the host to approve it from their server app." };
      case "expired":
        return { kind: "refused", title: "Nobody approved this device in time", message: "The request ran out after 10 minutes. Ask again when the host is around." };
      case "revoked":
        return { kind: "refused", title: "The host removed this device", message: "This device can't join this server anymore unless the host approves it again." };
      default:
        return null;
    }
  });
</script>

{#if prompt && session}
  <div class="scrim">
    <div class="dialog card" role="alertdialog" aria-modal="true" aria-labelledby="device-title">
      {#if prompt.kind === "waiting"}
        <h2 id="device-title">Waiting for the host to approve this device</h2>
        <p>Ask the host to approve "{$settings.deviceName}" and check that they see the same code.</p>
        <p class="code mono" dir="ltr">{prompt.fingerprint}</p>
        <p class="dim">This device: {$settings.deviceName}</p>
        <div class="row"><button class="button quiet" onclick={() => session.disconnect()}>Cancel</button></div>
      {:else if prompt.kind === "notApproved"}
        <h2 id="device-title">This device isn't approved on this server</h2>
        <p>The host only lets in devices they approved. Ask them for access?</p>
        <div class="row">
          <button class="button quiet" onclick={() => session.disconnect()}>Close</button>
          <button class="button" onclick={() => session.rejoin(true)}>Request access</button>
        </div>
      {:else}
        <h2 id="device-title">{prompt.title}</h2>
        <p>{prompt.message}</p>
        <div class="row">
          <button class="button quiet" onclick={() => session.disconnect()}>Close</button>
          <button class="button" onclick={() => session.rejoin(true)}>Try again</button>
        </div>
      {/if}
    </div>
  </div>
{/if}

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: var(--scrim);
    display: grid;
    place-items: center;
    padding: 16px;
    z-index: 50;
  }
  .dialog {
    max-width: 440px;
    display: grid;
    gap: 12px;
  }
  h2 {
    margin: 0;
    font-size: 19px;
  }
  p {
    margin: 0;
  }
  .code {
    justify-self: start;
    font-size: 30px;
    font-weight: 700;
    letter-spacing: 2px;
    color: var(--accent);
    background: var(--surface-high);
    border-radius: 8px;
    padding: 8px 12px;
  }
  .row {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
</style>
