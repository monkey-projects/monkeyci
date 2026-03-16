const defaultTheme = require('tailwindcss/defaultTheme')

module.exports = {
    // in prod look at shadow-cljs output file
    // in dev look at runtime, which will change files that are actually compiled;
    // postcss watch should be a whole lot faster
    content: process.env.NODE_ENV == 'production' ? ["./target/js/dashboard/js/main.js"] : ["./target/js/dashboard/js/cljs-runtime/*.js"],
    theme: {
	extend: {
	    fontFamily: {
		sans: ["Inter var", ...defaultTheme.fontFamily.sans],
	    },
	},
    },
    safelist: [
	'delay-1', 'delay-2', 'delay-3', 'delay-4',
	'metric-success', 'metric-neutral', 'metric-danger',
    ]
}
