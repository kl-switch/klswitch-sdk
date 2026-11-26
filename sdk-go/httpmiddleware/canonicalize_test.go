package httpmiddleware

import "testing"

// The regression: a host integrating without an adapter emitted chi/gin-style "{id}" templates.
// Rules whose selectors carried no path parameter still matched, so the integration looked
// healthy — only the parameterised rules silently missed.
func TestCanonicalizeTemplate(t *testing.T) {
	cases := []struct{ in, want string }{
		{"/api/items/{id}", "/api/items/:id"},
		{"/api/items/<id>", "/api/items/:id"},
		{"/api/items/<int:id>", "/api/items/:id"},
		{"/api/items/:id", "/api/items/:id"},
		{"/static/{file_path:path}", "/static/:file_path"},
		{"/static/<path:filename>", "/static/:filename"},
		{"/api/items/{id}/reviews/{reviewId}", "/api/items/:id/reviews/:reviewId"},
		// Paths with no parameters must be untouched, wildcards included.
		{"/api/items", "/api/items"},
		{"/api/payments/*/refund", "/api/payments/*/refund"},
		{"/api/items/**", "/api/items/**"},
		{"", ""},
	}
	for _, c := range cases {
		if got := CanonicalizeTemplate(c.in); got != c.want {
			t.Errorf("CanonicalizeTemplate(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
