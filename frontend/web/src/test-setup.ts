// O jsdom não desenha em canvas: sem contexto, o gráfico não é criado e a tabela continua com os números.
HTMLCanvasElement.prototype.getContext = (() => null) as typeof HTMLCanvasElement.prototype.getContext;
