# BlockStorm

Minijuego de supervivencia para **Paper 1.21+**: los bloques caen del cielo, cada vez más rápido,
y gana quien sobreviva más tiempo. Sin dependencias (no necesita WorldEdit ni Vault).


## Configurar una arena (2 minutos)
1. `/bs create mi_arena`
2. `/bs wand` → clic izquierdo y derecho en dos esquinas opuestas del volumen de juego
   (de la superficie del suelo hasta la altura desde la que caerán los bloques).
3. `/bs setzone mi_arena`
4. Ponte en el lobby → `/bs setlobby mi_arena`
5. Ponte dentro de la zona → `/bs setspawn mi_arena`
6. `/bs setmin mi_arena 2` · `/bs setmax mi_arena 10`
7. (opcional) `/bs palette mi_arena red_concrete blue_concrete ...`
8. `/bs join mi_arena` o `/bs join` para abrir el menú.

Requisitos de la arena: suelo sólido bajo la zona, paredes verticales (o la opción
`out-of-bounds-eliminates` los elimina si salen), y la capa superior de la zona libre de bloques.

## Comandos
Jugadores: `/bs join [arena]`, `leave`, `list`, `stats [jugador]`, `top [best|wins|games]`

Admin (`blockstorm.admin`): `create`, `delete`, `wand`, `setzone`, `setlobby`, `setspawn`,
`setmin`, `setmax`, `palette`, `enable`, `disable`, `info`, `forcestart`, `forcestop`, `reload`

## Permisos
`blockstorm.play` (todos) · `blockstorm.stats` (todos) · `blockstorm.admin` (op) · `blockstorm.bypass` (op)

## Qué hace diferente al diseño antiguo
- Solo elimina los bloques que **él mismo** colocó: la decoración de la arena nunca se borra.
- Inventario guardado en disco al entrar: si el servidor se cae, el jugador lo recupera al volver.
- Colisión de bloques calculada por el plugin (no depende del bug de Paper con los bloques que caen).
- Dificultad progresiva por niveles totalmente configurable (`config.yml > difficulty`).
- Scoreboard, bossbar, títulos y sonidos; mensajes MiniMessage con archivos de idioma (en/es).
- Estadísticas y top 10 guardadas de forma asíncrona.
- Recompensas por comandos de consola (`rewards.winner`, `rewards.participation`): compatible con cualquier economía.
