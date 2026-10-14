<script lang="ts">
  import { iconPaths, type IconName } from "../lib/ui/icons";

  /**
   * A Material icon in the text's colour. Decorative by default (the button or link beside it says what it does);
   * give it a [label] when it stands alone.
   */
  let { name, size = 20, label }: { name: IconName; size?: number; label?: string } = $props();
</script>

<svg
  class="icon"
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="currentColor"
  role={label ? "img" : undefined}
  aria-label={label}
  aria-hidden={label ? undefined : "true"}
  focusable="false"
>
  {@html iconPaths(name)}
</svg>

<style>
  .icon {
    flex: none;
    display: inline-block;
    vertical-align: middle;
  }
</style>
