ENTRY(_start)
SECTIONS
{
    . = 0;
    .vectors : { KEEP(*(.vectors)) }
    . = 0x20000;
    .text : { KEEP(*(.text.entry)) *(.text*) }
    .rodata : { *(.rodata*) }
    .data : { *(.data*) *(.sdata*) }
    .got : { *(.got) *(.toc) }
    .bss : { *(.bss*) *(.sbss*) *(COMMON) }
    /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
