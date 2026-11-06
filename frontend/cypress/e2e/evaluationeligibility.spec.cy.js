// UC-STU-deactivate-student / BR-evaluation-active-evaluatee: the peer evaluation form offers only active teammates,
// for new forms and for forms loaded from saved evaluations, and rebuilds itself after a refused save. Every API call
// the page makes is stubbed; the backend's own tests cover the refusal itself (UC-EVA-submit-evaluation 9a).
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const ok = (data, message = 'OK') => ({ flag: true, code: 200, message, data })

const criteria = [1, 2, 3].map((criterionId) => ({
  criterionId,
  criterion: `Criterion ${criterionId}`,
  description: `C${criterionId}`,
  maxScore: 10
}))

const student = (id, firstName, enabled) => ({
  id,
  username: `${firstName.toLowerCase()}@abc.edu`,
  firstName,
  lastName: 'Teammate',
  email: `${firstName.toLowerCase()}@abc.edu`,
  enabled,
  teamId: 1,
  teamName: 'Team1'
})

const saved = (evaluationId, evaluateeId, name) => ({
  evaluationId,
  week: 'any',
  evaluatorId: 4,
  evaluateeId,
  evaluateeName: name,
  ratings: criteria.map((c) => ({
    criterionId: c.criterionId,
    criterion: c.criterion,
    actualScore: 9
  })),
  publicComment: 'kept',
  privateComment: 'kept'
})

describe('Peer evaluation eligibility', () => {
  let roster
  let savedEvaluations

  beforeEach(() => {
    cy.login('j.smith@abc.edu', '123456')
    cy.intercept('GET', '**/students/teams/*', (req) => req.reply(ok(roster))).as('roster')
    cy.intercept('GET', '**/evaluations/evaluators/*/week/*', (req) =>
      req.reply(ok(savedEvaluations))
    ).as('saved')
    cy.intercept(
      'GET',
      '**/sections/*',
      ok({ sectionId: 2, sectionName: '2023-2024', rubricId: 1 })
    )
    cy.intercept('GET', '**/rubrics/*', ok({ rubricId: 1, rubricName: 'Rubric', criteria }))
    cy.intercept('POST', '**/evaluations/weeks/*/receipt', ok(null)).as('receipt')
  })

  function rows() {
    return cy.get('.el-table__body-wrapper .el-table__row')
  }

  it('builds new forms for active teammates only (EVA step 2)', () => {
    roster = [student(4, 'John', true), student(5, 'Eric', false), student(6, 'Jerry', true)]
    savedEvaluations = []
    cy.visit('/evaluations/submit-evaluations')
    cy.wait(['@roster', '@saved'])

    rows().should('have.length', 2)
    rows()
      .should('contain', 'John Teammate')
      .and('contain', 'Jerry Teammate')
      .and('not.contain', 'Eric Teammate')
  })

  it('leaves a deactivated teammate out of the loaded editing forms (EVA step 7)', () => {
    roster = [student(4, 'John', true), student(5, 'Eric', false), student(6, 'Jerry', true)]
    savedEvaluations = [
      saved(1, 4, 'John Teammate'),
      saved(2, 5, 'Eric Teammate'),
      saved(3, 6, 'Jerry Teammate')
    ]
    cy.visit('/evaluations/submit-evaluations')
    cy.wait(['@roster', '@saved'])

    rows().should('have.length', 2).and('not.contain', 'Eric Teammate')
    cy.contains('button', 'Update').should('exist')
  })

  it('rebuilds the forms after a refused save, without retrying (EVA 9a)', () => {
    // Given: the page loaded while Eric was active
    roster = [student(4, 'John', true), student(5, 'Eric', true), student(6, 'Jerry', true)]
    savedEvaluations = []
    let posts = 0
    cy.intercept('POST', '**/evaluations', (req) => {
      posts += 1
      if (req.body.evaluateeId === 5) {
        req.reply({
          statusCode: 400,
          body: {
            flag: false,
            code: 400,
            message: 'The selected evaluatee is deactivated.',
            data: null
          }
        })
      } else {
        req.reply(ok({ ...req.body, evaluationId: 100 + req.body.evaluateeId }))
      }
    }).as('submit')
    cy.visit('/evaluations/submit-evaluations')
    cy.wait(['@roster', '@saved'])
    rows().should('have.length', 3)

    // When: Eric is deactivated before John submits; the other two saves succeed
    cy.then(() => {
      roster = [student(4, 'John', true), student(5, 'Eric', false), student(6, 'Jerry', true)]
      savedEvaluations = [saved(104, 4, 'John Teammate'), saved(106, 6, 'Jerry Teammate')]
    })
    cy.contains('button', 'Submit').click()

    // Then: the error is shown, the roster and saved evaluations are reloaded, and Eric's stale form is gone
    cy.contains('.el-message', 'The selected evaluatee is deactivated.')
    cy.wait(['@roster', '@saved'])
    rows().should('have.length', 2).and('not.contain', 'Eric Teammate')
    cy.contains('button', 'Update').should('exist')
    cy.then(() => expect(posts).to.equal(3)) // No silent retry
    cy.get('@receipt.all').should('have.length', 0) // No confirmation email for a failed submission
  })
})
